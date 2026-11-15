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
 * XSS JSON body 补充覆盖（REQ-20261114-120106、AC-4）：round1 打回单 wire 形态 b 条钉死——
 * 默认字节级原样透传（含富文本）、仅 escape-fields 配置的 JSON 顶层字段字符串值/字符串数组元素
 * 强制转义、嵌套路径与对象不递归。既有 XssCleanFilterTest 已覆盖无黑名单透传、顶层字符串值转义
 * 与解析失败透传，本类补充数组元素/嵌套不递归/多字段/关闭开关/根数组形态。
 */
class REQ20261114120106XssJsonBodySupplementTest {

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

    private MockServerWebExchange jsonPost(String body) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));
    }

    @Test
    void should_escapeStringArrayElements_when_blacklistedFieldIsArray() {
        // wire 形态 b 条：字符串数组元素同样强制转义
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("content")));
        MockServerWebExchange exchange = jsonPost("{\"content\":[\"<a>\",\"ok\"]}");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        String body = bodyOf(captured.get(0));
        assertThat(body).contains("\"&lt;a&gt;\"");
        assertThat(body).contains("\"ok\"");
    }

    @Test
    void should_notRecurseIntoNestedObjects_when_blacklistedFieldAppearsNested() {
        // wire 形态 b 条：嵌套路径与对象不递归，meta.content 不转义
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("content")));
        String raw = "{\"meta\":{\"content\":\"<b>keep</b>\"}}";
        MockServerWebExchange exchange = jsonPost(raw);
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo(raw);
    }

    @Test
    void should_escapeEveryConfiguredField_when_multipleBlacklistEntries() {
        // escape-fields 为字段名列表：命中每个配置字段的顶层字符串值
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("title", "digest")));
        MockServerWebExchange exchange = jsonPost(
                "{\"title\":\"<t>\",\"digest\":\"<d>\",\"note\":\"<n>\"}");
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        String body = bodyOf(captured.get(0));
        assertThat(body).contains("\"&lt;t&gt;\"");
        assertThat(body).contains("\"&lt;d&gt;\"");
        assertThat(body).contains("<n>");
    }

    @Test
    void should_passThroughJsonUnchanged_when_disabledEvenWithEscapeFields() {
        // 开关 acme.gateway.xss.enabled=false：整体旁路，含黑名单字段也不转义
        XssCleanFilter filter = new XssCleanFilter(config(false, List.of(), List.of("content")));
        String raw = "{\"content\":\"<b>bold</b>\"}";
        MockServerWebExchange exchange = jsonPost(raw);
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo(raw);
    }

    @Test
    void should_passThroughTopLevelArrayUnchanged_when_rootIsArray() {
        // 顶层形态钉死：仅 JSON 顶层字段参与黑名单，根为数组时无顶层字段可命中，整体原样
        XssCleanFilter filter = new XssCleanFilter(config(true, List.of(), List.of("content")));
        String raw = "[{\"content\":\"<x>\"}]";
        MockServerWebExchange exchange = jsonPost(raw);
        StepVerifier.create(filter.filter(exchange, chain())).verifyComplete();
        assertThat(bodyOf(captured.get(0))).isEqualTo(raw);
    }
}
