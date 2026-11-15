package com.acme.framework.gateway.interceptor;

import com.acme.framework.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.MediaType;
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
 * XSS 清洗过滤器测试（REQ-20261114-120106、AC-3/AC-4）：query/form 转义、JSON 默认不误伤、
 * 字段黑名单强制转义、豁免路径与开关分支。
 */
class XssCleanFilterTest {

    private final List<ServerWebExchange> captured = new ArrayList<>();

    private GatewayFilterChain chain() {
        return exchange -> {
            captured.add(exchange);
            return Mono.empty();
        };
    }

    private static GatewayProperties.Xss config(boolean enabled, List<String> exemptPaths,
                                                List<String> escapeFields) {
        GatewayProperties.Xss config = new GatewayProperties.Xss();
        config.setEnabled(enabled);
        config.setExemptPaths(exemptPaths);
        config.setEscapeFields(escapeFields);
        return config;
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
    void should_escapeScript_when_queryContainsHtml() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/x?q=<script>alert(1)</script>").build());
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void should_escapeQuoteAndAmp_when_queryContainsThem() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/x?a=\"x\"&b=y&z=c'd").build());
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        var params = captured.get(0).getRequest().getQueryParams();
        assertThat(params.getFirst("a")).isEqualTo("&quot;x&quot;");
        assertThat(params.getFirst("b")).isEqualTo("y");
        assertThat(params.getFirst("z")).isEqualTo("c&#x27;d");
    }

    @Test
    void should_passThrough_when_exemptPath() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of("/public/**"), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/public/x?q=<script>").build());
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q")).isEqualTo("<script>");
    }

    @Test
    void should_passThrough_when_disabled() {
        XssCleanFilter filter = new XssCleanFilter(config(false, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/x?q=<script>").build());
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q")).isEqualTo("<script>");
    }

    @Test
    void should_escapeFormValue_when_formSubmitted() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("a=<script>&b=2"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo("a=%26lt%3Bscript%26gt%3B&b=2");
    }

    @Test
    void should_notTouchJsonBody_when_noEscapeFields() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        String raw = "{\"content\":\"<script>富文本</script>\"}";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(raw));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo(raw);
    }

    @Test
    void should_escapeBlacklistedFieldOnly_when_escapeFieldsConfigured() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("content")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"content\":\"<b>bold</b>\",\"other\":\"<i>keep</i>\"}"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        String body = bodyOf(captured.get(0));
        assertThat(body).contains("\"content\":\"&lt;b&gt;bold&lt;/b&gt;\"");
        assertThat(body).contains("<i>keep</i>");
    }

    @Test
    void should_passThroughRawBody_when_jsonUnparsable() {
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("content")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("not-json"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo("not-json");
    }

    @Test
    void escapeHtml_shouldEscapeAmpFirst_when_valueContainsEntities() {
        assertThat(XssCleanFilter.escapeHtml("&lt;a&gt;")).isEqualTo("&amp;lt;a&amp;gt;");
        assertThat(XssCleanFilter.escapeHtml("plain")).isEqualTo("plain");
    }
}
