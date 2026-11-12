package com.acme.framework.gateway.internal;

import com.acme.common.core.ErrorCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关自身错误标准响应输出（FR-025、8.8、Q-D11）：Result 信封 + 语义化状态码；仅网关自身错误，
 * 业务响应不二次包装（BR-010）；手工输出信封四字段（data=null），不引入三方序列化栈（BR-020）。
 */
public final class ErrorBodies {

    private ErrorBodies() {
    }

    /** 写标准失败响应（Q-D11）。 */
    public static Mono<Void> write(ServerHttpResponse response, int status, ErrorCode errorCode) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"success\":false,\"code\":\"" + escape(errorCode.code()) + "\",\"message\":\""
                + escape(errorCode.defaultMessage()) + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
