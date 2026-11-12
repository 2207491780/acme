package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.trace.support.TraceScope;
import com.acme.framework.trace.support.Traces;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * HTTP 通道 trace 入口组件（FR-002）：仅处理 trace 头（AC-016；身份/上下文头归 framework-web，Modules.md 2.4.1）。
 * 入站复用或生成（两头独立，DQ-001）、处理期绑定、出站响应同值头、结束清理（BR-014）。
 * 本期仅 Servlet 形态（DQ-002）。
 */
public final class TraceHttpFilter implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        TraceContext ctx = Traces.resolve(
                httpRequest.getHeader(TraceConstants.TRACE_ID_HEADER),
                httpRequest.getHeader(TraceConstants.SPAN_ID_HEADER));
        try (TraceScope scope = TraceScope.open(ctx)) {
            httpResponse.setHeader(TraceConstants.TRACE_ID_HEADER, ctx.traceId());
            httpResponse.setHeader(TraceConstants.SPAN_ID_HEADER, ctx.spanId());
            chain.doFilter(request, response);
        }
    }

    /** HIGHEST_PRECEDENCE 段位：尽早绑定使 MDC 覆盖面最大（架构设计 3.3）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
