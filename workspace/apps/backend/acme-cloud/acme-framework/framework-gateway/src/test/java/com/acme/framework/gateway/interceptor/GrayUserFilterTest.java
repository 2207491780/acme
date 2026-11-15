package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.support.UserTokenClaims;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度用户选择器测试（REQ-20261114-120106、AC-5/AC-6）：白名单命中注入标记头、
 * 未命中/未认证/关闭不注入、伪造标记头剥离、确定性哈希恒定且分布可判。
 */
class GrayUserFilterTest {

    private final List<ServerWebExchange> captured = new ArrayList<>();

    private GatewayFilterChain chain() {
        return exchange -> {
            captured.add(exchange);
            return Mono.empty();
        };
    }

    private static GatewayProperties.Gray config(boolean enabled, List<String> users, int percent) {
        GatewayProperties.Gray config = new GatewayProperties.Gray();
        config.setEnabled(enabled);
        config.setUsers(users);
        config.setPercent(percent);
        return config;
    }

    private static MockServerWebExchange exchange(String userId) {
        return exchange(userId, null);
    }

    /** spoofedGrayHeader 非空时构造期注入伪造灰度头（MockServerWebExchange 请求头只读，不能事后 set）。 */
    private static MockServerWebExchange exchange(String userId, String spoofedGrayHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("http://localhost/admin/v1/system/users");
        if (spoofedGrayHeader != null) {
            builder.header("X-Acme-Gray", spoofedGrayHeader);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        if (userId != null) {
            exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS,
                    new UserTokenClaims(userId, null, Channel.B, Set.of(), "access", "jti-1"));
        }
        return exchange;
    }

    @Test
    void should_injectGrayHeader_when_whitelistHit() {
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 0));
        MockServerWebExchange exchange = exchange("u1", "spoofed");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void should_notInject_when_whitelistMiss() {
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u2"), 0));
        MockServerWebExchange exchange = exchange("u1", "spoofed");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isNull();
    }

    @Test
    void should_notInject_when_unauthenticated() {
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 100));
        MockServerWebExchange exchange = exchange(null, "spoofed");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isNull();
    }

    @Test
    void should_notInject_when_disabledButSpoofedHeaderStripped() {
        GrayUserFilter filter = new GrayUserFilter(config(false, List.of("u1"), 0));
        MockServerWebExchange exchange = exchange("u1", "spoofed");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isNull();
    }

    @Test
    void should_injectGrayHeader_when_percentHit() {
        // 构造百分百命中边界：percent=100 直接命中
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of(), 100));
        MockServerWebExchange exchange = exchange("any-user");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void percentHit_should_beDeterministicForSameUser_when_repeatedInvocations() {
        boolean expected = GrayUserFilter.percentHit("gray-user-1", 50);
        for (int i = 0; i < 1000; i++) {
            assertThat(GrayUserFilter.percentHit("gray-user-1", 50)).isEqualTo(expected);
        }
    }

    @Test
    void percentHit_should_splitStably_when_largeSample() {
        int hits = 0;
        int sample = 2000;
        for (int i = 0; i < sample; i++) {
            if (GrayUserFilter.percentHit("user-" + i, 50)) {
                hits++;
            }
        }
        // 大样本 50% 规则：命中比例落在 40%~60%（确定性哈希分布可判，AC-6）
        assertThat(hits).isBetween(sample * 2 / 5, sample * 3 / 5);
    }

    @Test
    void percentHit_should_returnFalseForZeroAndTrueForHundred_when_bounds() {
        assertThat(GrayUserFilter.percentHit("u", 0)).isFalse();
        assertThat(GrayUserFilter.percentHit("u", 100)).isTrue();
        assertThat(GrayUserFilter.percentHit("u", -5)).isFalse();
    }
}
