package com.acme.framework.gateway.interceptor;

import com.acme.framework.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * XSS 清洗过滤器（REQ-20261114-120106、AC-3/AC-4）：query 与 form（application/x-www-form-urlencoded）
 * 字段值做 HTML 实体转义（{@code & < > " '} → {@code &amp; &lt; &gt; &quot; &#x27;}）；
 * JSON body 默认不转义（保护公告富文本等业务，REQ-20261114-120102 确认记录②——公告正文在 JSON body 内），
 * 仅 {@code acme.gateway.xss.escape-fields} 配置的字段黑名单强制转义（顶层字段，字符串值或字符串数组元素）；
 * 豁免路径（{@code acme.gateway.xss.exempt-paths}，Ant 风格）命中的请求整体原样透传。
 * 清洗失败（解析异常）不阻断请求：告警并原样透传（清洗器不是校验器）。
 */
public final class XssCleanFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(XssCleanFilter.class);

    private final GatewayProperties.Xss config;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public XssCleanFilter(GatewayProperties.Xss config) {
        this.config = config;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!config.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (isExempt(path)) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = cleanQuery(exchange.getRequest());
        MediaType contentType = request.getHeaders().getContentType();
        boolean isForm = contentType != null && MediaType.APPLICATION_FORM_URLENCODED.includes(contentType);
        boolean isJson = contentType != null && MediaType.APPLICATION_JSON.includes(contentType)
                && !config.getEscapeFields().isEmpty();
        if (!isForm && !isJson) {
            // JSON body 默认不转义（AC-4）：仅透传 query 清洗结果
            return chain.filter(exchange.mutate().request(request).build());
        }
        return DataBufferUtils.join(request.getBody())
                .map(buffer -> {
                    try {
                        return buffer.toString(StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty("")
                .flatMap(rawBody -> {
                    String cleaned = cleanBody(rawBody, isForm);
                    // JSON 无黑名单命中（cleanJsonFields 返回 null）或解析失败：原 body 透传
                    return chain.filter(rebuild(exchange, request, cleaned != null ? cleaned : rawBody));
                });
    }

    /** body 清洗（form 全字段转义；JSON 仅字段黑名单）；清洗失败返回原 body（不阻断）。 */
    private String cleanBody(String rawBody, boolean isForm) {
        if (rawBody.isEmpty()) {
            return rawBody;
        }
        try {
            return isForm ? cleanForm(rawBody) : cleanJsonFields(rawBody);
        } catch (Exception ex) {
            log.warn("XSS 清洗 body 解析失败，原样透传：{}", ex.getClass().getName());
            return rawBody;
        }
    }

    /** query 参数 HTML 实体转义（重建 URI）；手工解析 rawQuery，不依赖 UriComponents 的解码歧义；解析失败原样透传。 */
    private ServerHttpRequest cleanQuery(ServerHttpRequest request) {
        URI uri = request.getURI();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return request;
        }
        try {
            boolean changed = false;
            StringBuilder query = new StringBuilder();
            String[] pairs = rawQuery.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    query.append('&');
                }
                String pair = pairs[i];
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    // 空值参数（?a）：归一为 name=（wire 形态 a，服务侧按空值消费）
                    String name = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    query.append(encodeQueryParam(name)).append('=');
                    changed = true;
                    continue;
                }
                String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                String escaped = escapeHtml(value);
                if (!escaped.equals(value)) {
                    changed = true;
                }
                query.append(encodeQueryParam(name)).append('=').append(encodeQueryParam(escaped));
            }
            if (!changed) {
                return request;
            }
            String base = uri.toString();
            int queryIndex = base.indexOf('?');
            String newUri = (queryIndex >= 0 ? base.substring(0, queryIndex) : base) + '?' + query;
            return request.mutate().uri(URI.create(newUri)).build();
        } catch (RuntimeException ex) {
            log.warn("XSS 清洗 query 解析失败，原样透传：{}", ex.getClass().getName());
            return request;
        }
    }

    /** form body：每字段值先 URL 解码 → HTML 实体转义 → 重新编码。 */
    private String cleanForm(String rawBody) {
        StringBuilder sb = new StringBuilder(rawBody.length());
        String[] pairs = rawBody.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (i > 0) {
                sb.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                sb.append(pair);
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            sb.append(name).append('=').append(URLEncoder.encode(escapeHtml(decoded), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** JSON body：仅字段黑名单（顶层字段）的字符串值/字符串数组元素转义；无变化返回 null（原样透传）。 */
    private String cleanJsonFields(String rawBody) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(rawBody);
        if (!(root instanceof ObjectNode object) || config.getEscapeFields().isEmpty()) {
            return null;
        }
        boolean changed = false;
        for (String field : config.getEscapeFields()) {
            JsonNode node = object.get(field);
            if (node instanceof TextNode text) {
                object.put(field, escapeHtml(text.asText()));
                changed = true;
            } else if (node instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    JsonNode item = array.get(i);
                    if (item instanceof TextNode text) {
                        array.set(i, TextNode.valueOf(escapeHtml(text.asText())));
                        changed = true;
                    }
                }
            }
        }
        return changed ? objectMapper.writeValueAsString(root) : null;
    }

    /** 重建请求（body 缓存装饰 + Content-Length 重算）。 */
    private ServerWebExchange rebuild(ServerWebExchange exchange, ServerHttpRequest request, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        headers.setContentLength(bytes.length);
        ServerHttpRequestDecorator decorated = new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() ->
                        Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    private boolean isExempt(String path) {
        for (String pattern : config.getExemptPaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** HTML 实体转义（{@code &} 先行，防二次转义）。 */
    static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 过滤链顺序 150：剥离伪造头（100）之后、鉴权（200）之前——后续链路统一消费干净请求。 */
    @Override
    public int getOrder() {
        return 150;
    }
}
