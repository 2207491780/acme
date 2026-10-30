package com.acme.framework.web.interceptor;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.web.internal.FilterResponses;
import com.acme.framework.web.support.ClientContextParser;
import com.acme.framework.web.support.ClientContextRejectedException;
import com.acme.framework.web.support.WebError;
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
 * 头提取与暂存过滤器（FR-007）：解析约定头构造 {@link ClientContextDraft} 暂存于请求属性，
 * 不做信任决策（Q-005）；请求结束 finally 清理暂存位（BR-025、5.6）。
 */
public final class ClientContextExtractFilter implements Filter, Ordered {

    private final ClientContextParser parser;

    public ClientContextExtractFilter(ClientContextParser parser) {
        this.parser = parser;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        try {
            httpRequest.setAttribute(ClientContextDraft.ATTRIBUTE_NAME, parser.parse(httpRequest));
        } catch (ClientContextRejectedException ex) {
            FilterResponses.writeFailure(httpResponse, WebError.BAD_REQUEST.httpStatus(),
                    WebError.BAD_REQUEST.code(), ex.getMessage());
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // BR-025：请求结束清理暂存属性（AuthenticatedSubject 由 security 回写、一并清理，5.6）
            httpRequest.removeAttribute(ClientContextDraft.ATTRIBUTE_NAME);
            httpRequest.removeAttribute(AuthenticatedSubject.ATTRIBUTE_NAME);
        }
    }

    /** HIGHEST_PRECEDENCE+100：访问日志之后、security 之前（5.1、7.1）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
