package com.acme.framework.web.interceptor;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.web.internal.AccessLogger;
import com.acme.framework.web.internal.StagingSnapshotRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * 服务侧访问日志过滤器（FR-006）：trace 之后、security 之前（外圈，取舍 #7），
 * 保证拒绝请求也可观测；日志失败不影响请求处理（FR-006 异常分支）。
 */
public final class AccessLogFilter implements Filter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final String serviceName;

    public AccessLogFilter(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        StagingSnapshotRequest snapshotRequest = new StagingSnapshotRequest(httpRequest);
        // 耗时用 nanoTime 换算毫秒，避免墙钟跳变（FR-006）
        long start = System.nanoTime();
        try {
            chain.doFilter(snapshotRequest, response);
        } finally {
            try {
                ClientContextDraft draft = (ClientContextDraft)
                        snapshotRequest.getAttribute(ClientContextDraft.ATTRIBUTE_NAME);
                AuthenticatedSubject subject = (AuthenticatedSubject)
                        snapshotRequest.getAttribute(AuthenticatedSubject.ATTRIBUTE_NAME);
                AccessLogger.log(serviceName, snapshotRequest, httpResponse.getStatus(),
                        (System.nanoTime() - start) / NANOS_PER_MILLI, draft, subject);
            } catch (RuntimeException ex) {
                // 日志失败不影响请求处理（FR-006 异常分支）
                log.debug("访问日志输出失败已忽略（FR-006 异常分支）", ex);
            }
        }
    }

    /** HIGHEST_PRECEDENCE+50：trace 之后、提取过滤器之前（5.1）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
