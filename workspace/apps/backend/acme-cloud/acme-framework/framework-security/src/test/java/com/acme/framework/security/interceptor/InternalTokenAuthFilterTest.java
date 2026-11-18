package com.acme.framework.security.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.support.InternalTokenClaims;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.support.TokenType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部令牌校验过滤器测试（FR-014/015/018、7.2/7.3/7.6、BR-009、AC-011~013）。
 */
class InternalTokenAuthFilterTest {

    private InternalTokenVerifier verifier(InternalTokenClaims claims, RuntimeException failure) {
        return token -> {
            if (failure != null) {
                throw failure;
            }
            return claims;
        };
    }

    private static InternalTokenClaims claims(Channel channel, TokenType type, String userId) {
        return new InternalTokenClaims(userId, "t-1", channel, Set.of("admin"), type, "acme-gateway", "jti");
    }

    private MockHttpServletRequest request(String uri, String channelHeader, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (channelHeader != null) {
            request.addHeader("X-Acme-Channel", channelHeader);
        }
        if (token != null) {
            request.addHeader("X-Acme-Token", token);
        }
        request.setAttribute(ClientContextDraft.ATTRIBUTE_NAME, new ClientContextDraft(null, "dev-1", "1.0"));
        return request;
    }

    @Test
    void validTokenBindsContextsAndSubject() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletRequest request = request("/admin/sample/items", "B", "tok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // 请求结束后上下文已清理（BR-025）
        assertThat(UserContext.get()).isNull();
        assertThat(ClientContext.get()).isNull();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void missingTokenRejected401() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(null, new BizException(SecurityError.TOKEN_MISSING)), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/admin/x", "B", null), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(SecurityError.TOKEN_MISSING.code());
    }

    @Test
    void refreshTypeRejected403() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.REFRESH, "u-1"), null), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/admin/x", "B", "tok"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(SecurityError.TOKEN_TYPE_MISMATCH.code());
    }

    @Test
    void channelMismatchRejected403() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.C, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/admin/x", "B", "tok"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(SecurityError.CHANNEL_MISMATCH.code());
    }

    @Test
    void prefixMismatchRejected403() throws Exception {
        // B 令牌走 C 前缀（7.3 越端双保险）
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/app/x", "B", "tok"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void publicPathSkipsAuthAndContext() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(null, new BizException(SecurityError.TOKEN_MISSING)), List.of("/public/**"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                // 公开端点不写上下文（BR-009）
                assertThat(UserContext.get()).isNull();
            }
        };
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void bindsSubjectForAccessLog() throws Exception {
        CapturingChain chain = new CapturingChain();
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletRequest request = request("/admin/x", "B", "tok");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.subject.userId()).isEqualTo("u-1");
    }

    // ---- G4 运行态联调修复回归：/internal/** 服务间路径豁免端别校验（11.1.3 internal 非端别作用域）----

    @Test
    void internalPathSkipsChannelCheckAndBindsUserContext() throws Exception {
        // B 端内签令牌调 /internal/**：端别前缀两不沾，修复前误判 CHANNEL_MISMATCH 403
        CapturingChain chain = new CapturingChain();
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletRequest request =
                request("/internal/v1/authorization/permissions/content:content:list", "B", "tok");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.passed).isTrue();
        assertThat(chain.subject.userId()).isEqualTo("u-1");
        // UserContext 正确绑定（userId / roles），供权限判定读取
        assertThat(chain.boundUser).isNotNull();
        assertThat(chain.boundUser.userId()).isEqualTo("u-1");
        assertThat(chain.boundUser.roles()).containsExactly("admin");
    }

    @Test
    void internalPathIgnoresDeclaredChannelConsistency() throws Exception {
        // 服务间调用不声明端别：declared 与 claims 不一致也不拒绝（全豁免语义，G4）
        CapturingChain chain = new CapturingChain();
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.ACCESS, "u-1"), null), List.of());
        MockHttpServletRequest request = request("/internal/v1/auth-profiles", "C", "tok");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.passed).isTrue();
    }

    @Test
    void internalPathStillEnforcesTokenType() throws Exception {
        // tokenType 校验对 internal 同样适用（内签令牌恒为 access；refresh 冒充拒绝）
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(
                verifier(claims(Channel.B, TokenType.REFRESH, "u-1"), null), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/v1/authorization/permissions/x", "B", "tok"),
                response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(SecurityError.TOKEN_TYPE_MISMATCH.code());
    }

    private static final class CapturingChain extends MockFilterChain {
        private AuthenticatedSubject subject;
        private UserContext boundUser;
        private boolean passed;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            passed = true;
            subject = (AuthenticatedSubject) req.getAttribute(AuthenticatedSubject.ATTRIBUTE_NAME);
            boundUser = UserContext.get();
        }
    }
}
