package com.acme.bootstrap.edge;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.core.BizException;
import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ChannelPrefix;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.internal.ResponseBodies;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.token.UserAccessClaims;
import com.acme.framework.security.token.UserTokenAccessVerifier;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 单体边缘认证过滤器（REQ-20261118-002130 确认④：无网关形态的边缘职责承接）——
 * 复刻网关过滤链的用户令牌面（AuthFilter + RevocationCheckFilter + InjectIdentityFilter 语义单源）：
 * <ol>
 *   <li>端别路由判定：仅 {@code /admin/**}（B 端）与 {@code /app/**}（C 端）进入用户令牌认证，
 *       其余路径（/ws/**、/internal/**、探针等）原样放行（网关 AuthFilter channelOf 同口径）；</li>
 *   <li>公开路径豁免：acme.security.public-paths 并集（与 InternalTokenAuthFilter 同清单，
 *       auth 8 条 + /ws/system/** + /actuator/**）——放行且不写上下文（BR-009/M-10）；</li>
 *   <li>用户令牌验签：{@link UserTokenAccessVerifier}（与网关验签同源实现，8.2 语义）——
 *       过期/无效 401，tokenType 非 access 拒绝（11.4 刷新令牌不得鉴权 API），
 *       channel claim 与路由前缀不符 → CHANNEL_MISMATCH 403（FR-019 异常分支）；</li>
 *   <li>撤销检查：同 JVM 直调 {@link SessionRedis#isRevoked}（即时失效 BR-006）；判定异常
 *       FAIL_OPEN 放行 + WARN（网关 RevocationCheckFilter 默认口径，可用性优先）；</li>
 *   <li>内部令牌注入：经 framework-security {@link InternalTokenIssuer}（JwsInternalTokenIssuer，
 *       acme.security.token.* 密钥域）签发 JWS 写入 {@code X-Acme-Token}，并注入 X-Acme-User-Id /
 *       Tenant-Id / Channel / Device-Id / App-Version 身份头（网关 InjectIdentityFilter 头集同口径）；
 *       放行给 InternalTokenAuthFilter（order=+200）完成上下文绑定（UserContext/TenantContext/
 *       ClientContext 写入与请求结束清理仍归该过滤器，BR-025 不动）。</li>
 * </ol>
 * order = HIGHEST_PRECEDENCE+150：web 提取过滤器（+100，ClientContextDraft 就绪）之后、
 * InternalTokenAuthFilter（+200）之前（确认④「InternalTokenAuthFilter 之前」）。错误响应
 * 走 framework-security ResponseBodies 标准信封（错误码段为 SYS-02 服务侧段——网关形态为
 * SYS-03 网关段，HTTP 状态一致，属双形态已记录差异，见 BACKEND.md 能力差异表）。
 */
public final class BootstrapAuthFilter implements Filter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAuthFilter.class);

    private final UserTokenAccessVerifier verifier;
    private final InternalTokenIssuer internalTokenIssuer;
    private final SessionRedis sessionRedis;
    private final List<String> publicPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public BootstrapAuthFilter(UserTokenAccessVerifier verifier, InternalTokenIssuer internalTokenIssuer,
                               SessionRedis sessionRedis, List<String> publicPaths) {
        this.verifier = verifier;
        this.internalTokenIssuer = internalTokenIssuer;
        this.sessionRedis = sessionRedis;
        this.publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();
        Channel channel = channelOf(path);
        if (channel == null || isPublicPath(path)) {
            // 非端别路径 / 公开路径：放行且不认证、不注入（网关 AuthFilter 同口径）
            chain.doFilter(request, response);
            return;
        }
        UserAccessClaims claims;
        try {
            claims = verifier.verifyAccess(extractToken(httpRequest));
        } catch (BizException ex) {
            // 验签失败/过期 → 401 标准信封（Q-D11 语义化状态码）
            ResponseBodies.writeFailure(httpResponse, ex.errorCode());
            return;
        }
        // 刷新令牌不得鉴权 API（11.4 会话策略）
        if (!"access".equals(claims.tokenType())) {
            ResponseBodies.writeFailure(httpResponse, SecurityError.UNAUTHORIZED);
            return;
        }
        // 越端判定（FR-019 异常分支、11.1.1）：channel claim 缺失或与路由前缀不符 → 403
        if (claims.channel() == null || claims.channel() != channel) {
            ResponseBodies.writeFailure(httpResponse, SecurityError.CHANNEL_MISMATCH);
            return;
        }
        // 撤销检查（Q-D02、BR-006）：异常 FAIL_OPEN 放行（判定单段，不覆盖下游链路）
        if (isRevokedFailOpen(claims.tokenId())) {
            ResponseBodies.writeFailure(httpResponse, SecurityError.UNAUTHORIZED);
            return;
        }
        // 注入要素缺失（userId）拒绝转发（FR-021 异常分支；签发见 injectIdentity）
        if (claims.userId() == null || claims.userId().isBlank()) {
            ResponseBodies.writeFailure(httpResponse, SecurityError.UNAUTHORIZED);
            return;
        }
        chain.doFilter(injectIdentity(httpRequest, claims), response);
    }

    /** 端别取自路由前缀（11.1.3）：B=/admin、C=/app；其余路径不进用户令牌认证。 */
    private static Channel channelOf(String path) {
        if (path.startsWith(ChannelPrefix.ADMIN)) {
            return Channel.B;
        }
        if (path.startsWith(ChannelPrefix.APP)) {
            return Channel.C;
        }
        return null;
    }

    private boolean isPublicPath(String path) {
        for (String pattern : publicPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 撤销判定单段（网关 RevocationCheckFilter FAIL_OPEN 口径：异常 WARN 放行，可用性优先）。 */
    private boolean isRevokedFailOpen(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        try {
            return sessionRedis.isRevoked(tokenId);
        } catch (RuntimeException ex) {
            log.warn("用户令牌撤销检查失败，FAIL_OPEN 放行：tokenId={}", tokenId);
            return false;
        }
    }

    /** 用户令牌取自 Authorization: Bearer（网关 AuthFilter 提取口径一致）。 */
    private static String extractToken(HttpServletRequest request) {
        String raw = request.getHeader("Authorization");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }

    /**
     * 签发内部令牌并注入身份头（网关 InjectIdentityFilter 语义同口径）；
     * deviceId/appVersion 取 web 提取层暂存 draft（+100 过滤器先于本过滤器，与网关
     * ExchangeAttrs 暂存同型）。包装头仅存活于本请求（InternalTokenAuthFilter 消费后即弃）。
     */
    private ServletRequest injectIdentity(HttpServletRequest request, UserAccessClaims claims) {
        String internalToken = internalTokenIssuer.issue(new AuthenticatedSubject(
                claims.userId(), claims.tenantId(), claims.channel(), claims.roles()));
        ClientContextDraft draft =
                (ClientContextDraft) request.getAttribute(ClientContextDraft.ATTRIBUTE_NAME);
        String deviceId = draft == null ? null : draft.deviceId();
        String appVersion = draft == null ? null : draft.appVersion();
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if (ServiceHeaders.SERVICE_TOKEN.equalsIgnoreCase(name)) {
                    return internalToken;
                }
                if (ServiceHeaders.USER_ID.equalsIgnoreCase(name)) {
                    return claims.userId();
                }
                if (ServiceHeaders.TENANT_ID.equalsIgnoreCase(name) && claims.tenantId() != null) {
                    return claims.tenantId();
                }
                if (ServiceHeaders.CHANNEL.equalsIgnoreCase(name) && claims.channel() != null) {
                    return claims.channel().name();
                }
                if (ServiceHeaders.DEVICE_ID.equalsIgnoreCase(name) && deviceId != null) {
                    return deviceId;
                }
                if (ServiceHeaders.APP_VERSION.equalsIgnoreCase(name) && appVersion != null) {
                    return appVersion;
                }
                return super.getHeader(name);
            }

            @Override
            public java.util.Enumeration<String> getHeaders(String name) {
                String injected = getHeader(name);
                if (injected != null) {
                    return java.util.Collections.enumeration(Set.of(injected));
                }
                return super.getHeaders(name);
            }
        };
    }

    /** +150：web 提取（+100）之后、InternalTokenAuthFilter（+200）之前（确认④）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }
}
