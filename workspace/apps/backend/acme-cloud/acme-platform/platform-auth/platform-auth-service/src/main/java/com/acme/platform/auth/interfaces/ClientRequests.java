package com.acme.platform.auth.interfaces;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录请求客户端信息捕获（REQ-20261114-120105 AC-1 登录日志 ip/ua 字段口径）：
 * IP 优先 X-Forwarded-For 首跳（经网关/代理链），缺失回退 remoteAddr；UA 原样截断至落库列宽。
 */
final class ClientRequests {

    private static final int MAX_IP_LENGTH = 64;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private ClientRequests() {
    }

    /** 登录来源 IP（X-Forwarded-For 首跳优先；空白段跳过；全部缺失回退 remoteAddr）。 */
    static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String candidate : forwarded.split(",")) {
                String ip = candidate.trim();
                if (!ip.isEmpty()) {
                    return truncate(ip, MAX_IP_LENGTH);
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? null : truncate(remoteAddr, MAX_IP_LENGTH);
    }

    /** User-Agent（截断至落库列宽；缺失 null）。 */
    static String resolveUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return userAgent == null || userAgent.isBlank() ? null : truncate(userAgent, MAX_USER_AGENT_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
