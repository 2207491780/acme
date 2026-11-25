package com.acme.platform.auth.security;

import com.acme.common.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 公开端点访问审计拦截器（REQ-20261125-001736 AC-1）：仅审计三个登录页公开端点的 GET 访问
 * （tenants / captcha-policy / public-config——租户枚举面保留为已知取舍，仅求可追溯，确认记录②）。
 *
 * <p>事件口径：logger {@code acme.security.public-access}，字段序 traceId/clientIp/endpoint/
 * tenantCode/ua（缺失填 {@code -}）；tenantCode 取请求参数（调用方声明值，公开端点预认证无租户上下文）；
 * traceId 取 framework-trace 绑定的 {@link TraceContext}；clientIp 取 X-Forwarded-For 首跳
 * （与登录日志 ClientRequests 口径一致），缺失回退 remoteAddr；UA 摘要截断至 256 并剥离控制字符。</p>
 *
 * <p>响应与行为零变化（AC-1）：preHandle 恒返回 true、不写响应；审计内部失败仅降级跳过，
 * 不影响端点语义/状态码/响应形状。</p>
 */
public class PublicAccessAuditInterceptor implements HandlerInterceptor {

    /** 结构化访问日志 logger（AC-1 约定名）。 */
    public static final String AUDIT_LOGGER_NAME = "acme.security.public-access";

    /** 审计目标路径（GET；与注册 pattern 一致，双保险防非 GET 绕过 pattern 注册）。 */
    static final java.util.Set<String> AUDITED_PATHS = java.util.Set.of(
            "/admin/v1/auth/tenants",
            "/admin/v1/auth/captcha-policy",
            "/admin/v1/auth/public-config");

    private static final Logger log = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    private static final int MAX_CLIENT_IP_LENGTH = 64;
    private static final int MAX_USER_AGENT_LENGTH = 256;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            logAccess(request);
        } catch (RuntimeException ex) {
            // 审计失败不阻断访问、不改响应（AC-1 响应与行为零变化）
            log.debug("公开端点访问审计跳过：{}", ex.getMessage());
        }
        return true;
    }

    private void logAccess(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        String endpoint = request.getRequestURI();
        if (!AUDITED_PATHS.contains(endpoint)) {
            return;
        }
        log.info("public-access traceId={} clientIp={} endpoint={} tenantCode={} ua={}",
                traceId(), resolveClientIp(request), endpoint,
                tenantCode(request), summarizeUserAgent(request));
    }

    /** traceId（framework-trace 绑定值；未绑定记 {@code -}）。 */
    private static String traceId() {
        TraceContext trace = TraceContext.get();
        String traceId = trace == null ? null : trace.traceId();
        return traceId == null || traceId.isBlank() ? "-" : traceId;
    }

    /** tenantCode：请求参数声明值（trim；缺失/空白记 {@code -}）。 */
    private static String tenantCode(HttpServletRequest request) {
        String tenantCode = request.getParameter("tenantCode");
        return tenantCode == null || tenantCode.isBlank() ? "-" : tenantCode.trim();
    }

    /** 来源 IP：X-Forwarded-For 首跳优先（空白段跳过），缺失回退 remoteAddr（ClientRequests 同口径）。 */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String candidate : forwarded.split(",")) {
                String ip = candidate.trim();
                if (!ip.isEmpty()) {
                    return truncate(ip, MAX_CLIENT_IP_LENGTH);
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "-" : truncate(remoteAddr, MAX_CLIENT_IP_LENGTH);
    }

    /** UA 摘要：剥离控制字符（防日志注入）后截断（缺失/空白记 {@code -}）。 */
    private static String summarizeUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder(userAgent.length());
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            builder.append(c < 0x20 ? ' ' : c);
        }
        return truncate(builder.toString().trim(), MAX_USER_AGENT_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
