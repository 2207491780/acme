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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XSS 清洗补充覆盖（REQ-20261114-120106、AC-3）：round1 打回单 wire 形态 a 条钉死——
 * 仅转义 & < > " ' 五字符（&amp; 先行防二次转义）、query 解码后为实体形态、
 * form wire 为 URL 编码形态且服务解码后即实体形态、空值参数 ?a 归一为 a=；
 * 豁免路径 Ant 形态（精确路径 / ? 单字符通配）为 AMB-5 键面补充。
 * 既有 XssCleanFilterTest 覆盖基础转义/豁免/开关/form 基例，本类不重复。
 */
class REQ20261114120106XssEscapeRulesSupplementTest {

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

    /** 服务侧视角：按 form wire 约定对捕获到的 body 逐对 URL 解码。 */
    private static Map<String, String> decodedFormOf(ServerWebExchange exchange) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : bodyOf(exchange).split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            form.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return form;
    }

    @Test
    void should_notDoubleEscape_when_formValueIsConsecutiveAmpersands() {
        // &amp; 先行且只过一遍：&& → &amp;&amp;（逐字符转义；&amp;amp;amp; 等多遍转义形态钉死不允许）
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("a=%26%26"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(decodedFormOf(captured.get(0))).containsEntry("a", "&amp;&amp;");
    }

    @Test
    void should_reEscapeAmpersand_when_formValueContainsEntityForm() {
        // 值本身已是实体形态 &lt;tag&gt;：仅对 & 再转义一层，&lt; → &amp;lt;
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("a=%26lt%3Btag%26gt%3B"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(decodedFormOf(captured.get(0))).containsEntry("a", "&amp;lt;tag&amp;gt;");
    }

    @Test
    void should_wireUrlEncodedEntityForm_when_formContainsScriptPayload() {
        // wire 形态 a 条：form wire 为 URL 编码形态，服务解码后即实体形态 &lt;script&gt;alert(1)&lt;/script&gt;
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("q=%3Cscript%3Ealert(1)%3C%2Fscript%3E"));
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        Map<String, String> form = decodedFormOf(captured.get(0));
        assertThat(form).containsEntry("q", "&lt;script&gt;alert(1)&lt;/script&gt;");
        // wire 上不得携带未转义裸字符
        assertThat(bodyOf(captured.get(0))).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void should_normalizeEmptyQueryParameter_when_parameterHasNoValue() {
        // wire 形态 a 条：空值参数 ?a 归一为 a=
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/x?a").build());
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams()).containsKey("a");
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("a")).isEmpty();
    }

    @Test
    void should_exemptExactPathOnly_when_exemptPathHasNoWildcard() {
        // AMB-5：exempt-paths 为 Ant 风格；无通配时精确匹配，子路径不豁免
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of("/api/exact"), List.of()));
        MockServerWebExchange exact = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/api/exact?q=<b>").build());
        StepVerifier.create(filter.filter(exact, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q")).isEqualTo("<b>");

        MockServerWebExchange subPath = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/api/exact/sub?q=<b>").build());
        StepVerifier.create(filter.filter(subPath, chain())).verifyComplete();
        assertThat(captured.get(1).getRequest().getQueryParams().getFirst("q"))
                .isEqualTo("&lt;b&gt;");
    }

    @Test
    void should_exemptSingleCharWildcard_when_exemptPathUsesQuestionMark() {
        // AMB-5：? 单字符通配——/item? 命中 /items，不命中 /itemss
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of("/item?"), List.of()));
        MockServerWebExchange matched = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/items?q=<b>").build());
        StepVerifier.create(filter.filter(matched, chain())).verifyComplete();
        assertThat(captured.get(0).getRequest().getQueryParams().getFirst("q")).isEqualTo("<b>");

        MockServerWebExchange unmatched = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/itemss?q=<b>").build());
        StepVerifier.create(filter.filter(unmatched, chain())).verifyComplete();
        assertThat(captured.get(1).getRequest().getQueryParams().getFirst("q"))
                .isEqualTo("&lt;b&gt;");
    }
}
