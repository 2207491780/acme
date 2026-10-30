package com.acme.framework.security.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ChannelPrefix;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.internal.ResponseBodies;
import com.acme.framework.security.support.InternalTokenClaims;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.support.TokenType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * 内部令牌校验过滤器（FR-014/015/016/018）：web 提取过滤器（+100）之后、业务处理之前（7.1 order=+200）。
 * 校验通过后统一写入 UserContext/ClientContext/TenantContext 并回写 AuthenticatedSubject（7.6、Q-D04）；
 * 未校验请求（直连、公开端点）不写任何上下文（BR-009、M-10）；请求结束 finally 清理（BR-025）。
 */
public final class InternalTokenAuthFilter implements Filter, Ordered {

    private final InternalTokenVerifier verifier;
    private final List<String> publicPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public InternalTokenAuthFilter(InternalTokenVerifier verifier, List<String> publicPaths) {
        this.verifier = verifier;
        this.publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (isPublicPath(httpRequest.getRequestURI())) {
            // 公开端点放行且不写上下文（BR-009、M-10）
            chain.doFilter(request, response);
            return;
        }
        InternalTokenClaims claims;
        try {
            claims = verifier.verify(header(httpRequest, ServiceHeaders.SERVICE_TOKEN));
            validateTypeAndChannel(claims, httpRequest);
            if (claims.userId() == null || claims.userId().isBlank()) {
                throw new BizException(SecurityError.UNAUTHORIZED);
            }
        } catch (BizException ex) {
            ResponseBodies.writeFailure(httpResponse, ex.errorCode());
            return;
        }
        bindContexts(claims, httpRequest);
        try {
            chain.doFilter(request, response);
        } finally {
            // BR-025：请求结束清理三上下文（2.4.3）
            UserContext.clear();
            ClientContext.clear();
            TenantContext.clear();
        }
    }

    /** 令牌类型与端别声明校验（FR-015、7.3）：类型必须访问令牌；channel claim 与声明/前缀一致。
     *  服务间路径 {@code /internal/**} 非端别作用域（11.1.3 端别前缀只定义 /admin、/app 客户端路径），
     *  豁免端别声明/前缀校验（G4 运行态联调修复：internal URI 两不沾端别前缀会被误判 CHANNEL_MISMATCH）；
     *  tokenType 校验对 internal 同样适用（内签令牌恒为 access）。 */
    private void validateTypeAndChannel(InternalTokenClaims claims, HttpServletRequest request) {
        if (claims.tokenType() != TokenType.ACCESS) {
            throw new BizException(SecurityError.TOKEN_TYPE_MISMATCH);
        }
        if (isInternalPath(request.getRequestURI())) {
            return;
        }
        Channel declared = declaredChannel(request);
        if (declared != null && claims.channel() != null && declared != claims.channel()) {
            throw new BizException(SecurityError.CHANNEL_MISMATCH);
        }
        if (claims.channel() != null && !ChannelPrefix.matches(claims.channel(), request.getRequestURI())) {
            throw new BizException(SecurityError.CHANNEL_MISMATCH);
        }
    }

    /** 服务间端点路径（/internal/**）：非端别作用域，端别校验豁免（11.1.3）。 */
    private boolean isInternalPath(String path) {
        return pathMatcher.match("/internal/**", path);
    }

    /** 上下文统一写入（FR-018、7.6）：验签后的 claims + web 暂存 draft；随后回写 AuthenticatedSubject（4.4）。 */
    private void bindContexts(InternalTokenClaims claims, HttpServletRequest request) {
        ClientContextDraft draft = (ClientContextDraft) request.getAttribute(ClientContextDraft.ATTRIBUTE_NAME);
        Channel channel = claims.channel() != null ? claims.channel() : (draft == null ? null : draft.channel());
        String deviceId = draft == null ? null : draft.deviceId();
        String appVersion = draft == null ? null : draft.appVersion();
        UserContext.bind(new UserContext(claims.userId(), claims.roles()));
        TenantContext.bind(new TenantContext(claims.tenantId()));
        ClientContext.bind(new ClientContext(channel, deviceId, appVersion));
        request.setAttribute(AuthenticatedSubject.ATTRIBUTE_NAME,
                new AuthenticatedSubject(claims.userId(), claims.tenantId(), channel, claims.roles()));
    }

    private Channel declaredChannel(HttpServletRequest request) {
        String raw = header(request, ServiceHeaders.CHANNEL);
        if (raw == null) {
            ClientContextDraft draft = (ClientContextDraft) request.getAttribute(ClientContextDraft.ATTRIBUTE_NAME);
            return draft == null ? null : draft.channel();
        }
        try {
            return Channel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isPublicPath(String path) {
        for (String pattern : publicPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** HIGHEST_PRECEDENCE+200：web 提取过滤器（+100）之后（7.1）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }
}
