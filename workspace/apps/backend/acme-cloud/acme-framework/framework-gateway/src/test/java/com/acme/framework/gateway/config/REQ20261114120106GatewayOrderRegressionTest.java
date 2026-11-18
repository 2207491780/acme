package com.acme.framework.gateway.config;

import com.acme.framework.gateway.interceptor.GrayUserFilter;
import com.acme.framework.gateway.interceptor.XssCleanFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新增过滤器回归（REQ-20261114-120106、AC-7）：round1 打回单 wire 形态 e 条钉死过滤器顺序
 * Trace(HIGHEST) → DocGuard 50 → 剥头 100 → XSS 150 → 鉴权 200 → 撤销 250 → 限流 300 →
 * 注入 400 → 灰度 450 → 日志 500；并断言开关关闭时对既有链零影响
 * （XSS 关闭=整体旁路；灰度关闭=仅剥伪造头不注入）。装配风格照抄既有 GatewaySwitchTest，不改它。
 */
class REQ20261114120106GatewayOrderRegressionTest {

    private final List<ServerWebExchange> captured = new ArrayList<>();

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
            // 与 GatewaySwitchTest 同口径：测试占位密钥 + 桩件签发
            .withPropertyValues("acme.gateway.user-token.hmac-secret=req-20261114120106-order-regression-secret-0123456789")
            .withBean(InternalTokenIssuer.class, () -> subject -> "stub-token");

    private GatewayFilterChain chain() {
        return exchange -> {
            captured.add(exchange);
            return Mono.empty();
        };
    }

    private static String bodyOf(ServerWebExchange exchange) {
        StringBuilder sb = new StringBuilder();
        StepVerifier.create(exchange.getRequest().getBody())
                .thenConsumeWhile(buffer -> {
                    sb.append(StandardCharsets.UTF_8.decode(buffer.asByteBuffer()));
                    return true;
                })
                .verifyComplete();
        return sb.toString();
    }

    @Test
    void xssFilterOrderIsPinnedAt150() {
        assertThat(new XssCleanFilter(new GatewayProperties.Xss()).getOrder()).isEqualTo(150);
    }

    @Test
    void grayFilterOrderIsPinnedAt450() {
        assertThat(new GrayUserFilter(new GatewayProperties.Gray()).getOrder()).isEqualTo(450);
    }

    @Test
    void moduleSwitchOffUnregistersNewFilters() {
        runner.withPropertyValues("acme.gateway.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(XssCleanFilter.class);
            assertThat(context).doesNotHaveBean(GrayUserFilter.class);
        });
    }

    @Test
    void zeroConfigRegistersNewFiltersWithDefaults() {
        // xss 缺省开（matchIfMissing=true）注册；gray 缺省关（havingValue=true 无 matchIfMissing）不注册，
        // 显式开启后才注册——与 GatewayAutoConfiguration 装配口径一致
        runner.run(context -> {
            assertThat(context).hasBean("xssCleanFilter");
            assertThat(context).doesNotHaveBean("grayUserFilter");
        });
        runner.withPropertyValues("acme.gateway.gray.enabled=true").run(context -> {
            assertThat(context).hasBean("grayUserFilter");
        });
    }

    @Test
    void xssDisabledLeavesQueryAndJsonBodyUntouched() {
        // 开关关闭=与未引入一致：query 与 JSON body 均原样
        GatewayProperties.Xss xss = new GatewayProperties.Xss();
        xss.setEnabled(false);
        XssCleanFilter filter = new XssCleanFilter(xss);

        MockServerWebExchange queryExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/x?q=<script>").build());
        StepVerifier.create(filter.filter(queryExchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q")).isEqualTo("<script>");

        String raw = "{\"content\":\"<b>bold</b>\"}";
        MockServerWebExchange jsonExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(raw));
        StepVerifier.create(filter.filter(jsonExchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(1))).isEqualTo(raw);
    }

    @Test
    void grayDisabledStripsSpoofedHeaderOnlyAndLeavesOtherHeadersIntact() {
        GatewayProperties.Gray gray = new GatewayProperties.Gray();
        gray.setEnabled(false);
        GrayUserFilter filter = new GrayUserFilter(gray);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/admin/v1/system/users")
                        .header("X-Request-Id", "rid-1")
                        .header("X-Acme-Gray", "spoofed"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        // 伪造灰度头仍被剥离，其余头与既有链零交互
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Acme-Gray")).isNull();
        assertThat(captured.get(0).getRequest().getHeaders().getFirst("X-Request-Id")).isEqualTo("rid-1");
    }
}
