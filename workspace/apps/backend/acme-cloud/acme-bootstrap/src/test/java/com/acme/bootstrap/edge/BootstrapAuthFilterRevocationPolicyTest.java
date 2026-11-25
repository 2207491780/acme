package com.acme.bootstrap.edge;

import com.acme.common.context.Channel;
import com.acme.framework.security.revocation.RevocationCheckPolicy;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.token.UserAccessClaims;
import com.acme.framework.security.token.UserTokenAccessVerifier;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 边缘认证过滤器撤销检查开关测试（REQ-20261125-001736 AC-7）：fail-closed=true 且 Redis 检查异常
 * → 503 REVOCATION_CHECK_UNAVAILABLE 拒绝；默认 false → 与现状一致（放行 + WARN）。
 */
class BootstrapAuthFilterRevocationPolicyTest {

    private final UserTokenAccessVerifier verifier = mock(UserTokenAccessVerifier.class);
    private final InternalTokenIssuer internalTokenIssuer = mock(InternalTokenIssuer.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final FilterChain chain = mock(FilterChain.class);

    private static UserAccessClaims accessClaims() {
        return new UserAccessClaims("u-1", "t-1", Channel.B, Set.of("super_admin"), "access", "jti-1");
    }

    private BootstrapAuthFilter filter(boolean failClosed) {
        return new BootstrapAuthFilter(verifier, internalTokenIssuer, sessionRedis,
                new RevocationCheckPolicy(failClosed),
                List.of("/admin/v1/auth/tenants", "/actuator/**"));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/system/users");
        request.addHeader("Authorization", "Bearer token-1");
        return request;
    }

    @Test
    void should_rejectWith503RevocationCheckUnavailable_when_failClosedAndRedisDown() throws Exception {
        BootstrapAuthFilter filter = filter(true);
        when(verifier.verifyAccess(any())).thenReturn(accessClaims());
        when(sessionRedis.isRevoked(anyString())).thenThrow(new IllegalStateException("redis down"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains(SecurityError.REVOCATION_CHECK_UNAVAILABLE.code());
        assertThat(response.getContentAsString()).contains("撤销检查暂不可用");
        // 拒绝不放行下游（判定单段）
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void should_failOpenWithWarning_when_defaultPolicyAndRedisDown() throws Exception {
        BootstrapAuthFilter filter = filter(false);
        when(verifier.verifyAccess(any())).thenReturn(accessClaims());
        when(sessionRedis.isRevoked(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        // 默认 false 与现状完全一致：放行下游继续（上下文绑定归 InternalTokenAuthFilter）
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void should_continueDownstream_when_checkAvailableAndNotRevoked() throws Exception {
        BootstrapAuthFilter filter = filter(true);
        when(verifier.verifyAccess(any())).thenReturn(accessClaims());
        when(sessionRedis.isRevoked(anyString())).thenReturn(false);
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void should_notConsultRedis_withoutTokenId() throws Exception {
        BootstrapAuthFilter filter = filter(true);
        when(verifier.verifyAccess(any())).thenReturn(new UserAccessClaims(
                "u-1", "t-1", Channel.B, Set.of("super_admin"), "access", null));
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        // 无 tokenId 不查撤销（既有口径保留）
        verify(sessionRedis, never()).isRevoked(anyString());
        verify(chain).doFilter(any(), any());
    }
}
