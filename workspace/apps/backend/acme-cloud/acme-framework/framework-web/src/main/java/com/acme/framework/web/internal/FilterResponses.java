package com.acme.framework.web.internal;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器链路标准失败响应输出（FR-004 形态、Q-D11 语义化状态码 + Result 信封）；
 * 手工输出信封四字段（data=null），不依赖三方序列化栈（BR-020 不泄露实现细节）。
 */
public final class FilterResponses {

    private FilterResponses() {
    }

    /** 写标准失败响应（Result 信封，data=null）。 */
    public static void writeFailure(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = ("{\"success\":false,\"code\":\"" + escape(code) + "\",\"message\":\""
                + escape(message) + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body);
        }
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
