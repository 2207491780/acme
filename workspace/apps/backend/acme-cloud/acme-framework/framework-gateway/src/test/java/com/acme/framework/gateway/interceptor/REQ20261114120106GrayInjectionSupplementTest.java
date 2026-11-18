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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度注入补充覆盖（REQ-20261114-120106、AC-5/AC-6）：round1 打回单 AMB-1/AMB-2 与 wire 形态 c 条
 * 钉死——命中注入请求头 X-Acme-Gray 值恒为小写 on；未命中不携带该头（先剥离客户端伪造同名头）；
 * 白名单 userId 精确匹配与 percent 比例叠加（白名单命中与比例无关）；比例公式
 * SHA-256("acme-gray:"+userId) 前 8 字节大端序模 100 &lt; percent。既有 GrayUserFilterTest 已覆盖
 * 白名单基础命中/未命中、未认证、开关关闭剥离、percent=100 基例与重复调用确定性，本类不重复。
 */
class REQ20261114120106GrayInjectionSupplementTest {

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
    void should_injectLowercaseOn_when_hitDespiteUppercaseSpoofedHeader() {
        // 值恒为小写 on：伪造 ON 头先剥离，命中后重写为小写 on
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 0));
        MockServerWebExchange exchange = exchange("u1", "ON");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void should_injectOn_when_whitelistAndPercentBothHit() {
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 100));
        StepVerifier.create(filter.filter(exchange("u1"), chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void should_injectOn_when_whitelistHitDespiteZeroPercent() {
        // 白名单 userId 精确匹配命中即注入，与比例取值无关（percent=0 不压制白名单）
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 0));
        StepVerifier.create(filter.filter(exchange("u1"), chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void should_notInject_when_percentZeroAndUserNotInWhitelist() {
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of("u1"), 0));
        MockServerWebExchange exchange = exchange("u9", "on");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isNull();
    }

    @Test
    void should_injectOn_when_percentIsHundredEvenWithoutWhitelist() {
        // percent=100 边界：任意已认证用户全量命中
        GrayUserFilter filter = new GrayUserFilter(config(true, List.of(), 100));
        StepVerifier.create(filter.filter(exchange("any-user"), chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isEqualTo("on");
    }

    @Test
    void percentHit_should_matchPinnedSha256Formula() throws Exception {
        // wire 形态 c 条钉死公式：SHA-256("acme-gray:"+userId) 前 8 字节大端序（无符号 64 位）模 100 < percent
        // 独立按公式重算并与实现结果逐点对齐，钉死哈希口径不被替换
        for (String user : List.of("gray-user-1", "u-2", "user-three")) {
            for (int percent : new int[]{1, 50, 99}) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(("acme-gray:" + user).getBytes(StandardCharsets.UTF_8));
                long prefix = 0L;
                for (int i = 0; i < 8; i++) {
                    prefix = (prefix << 8) | (hash[i] & 0xFFL);
                }
                boolean expected = Long.remainderUnsigned(prefix, 100) < percent;
                assertThat(GrayUserFilter.percentHit(user, percent))
                        .as("user=%s percent=%d", user, percent)
                        .isEqualTo(expected);
            }
        }
    }
}
