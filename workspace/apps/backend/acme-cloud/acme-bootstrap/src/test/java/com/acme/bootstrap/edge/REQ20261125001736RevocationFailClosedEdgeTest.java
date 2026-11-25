package com.acme.bootstrap.edge;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.Channel;
import com.acme.framework.security.revocation.RevocationCheckPolicy;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.token.UserAccessClaims;
import com.acme.framework.security.token.UserTokenAccessVerifier;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * REQ-20261125-001736 AC-7/R2（边缘侧）：BootstrapAuthFilter 撤销检查段 × fail-closed 开关——
 * true=拒绝（HTTP 503 + 错误码 REVOCATION_CHECK_UNAVAILABLE）；默认 false=放行+WARN（现状）；
 * 与网关侧同开关同语义（同 {@link RevocationCheckPolicy} 语义载体）。
 *
 * <p>装配缝随交付对齐（{@code BootstrapAuthFilterRevocationPolicyTest} 同缝）：
 * 五参构造 (verifier, internalTokenIssuer, sessionRedis, policy, publicPaths List)，
 * policy 直接构造 {@code new RevocationCheckPolicy(failClosed)}；属性绑定与 bean 装配面
 * 由 framework-security 侧 {@code REQ20261125001736RevocationCheckPolicyTest} 覆盖。
 * 本类在交付缝上补两类断言：放行伴随 WARN 日志、失败注入用独立 tokenId 隔离。</p>
 */
class REQ20261125001736RevocationFailClosedEdgeTest {

    private static final String TOKEN_ID = "jti-001736";

    private final UserTokenAccessVerifier verifier = mock(UserTokenAccessVerifier.class);
    private final InternalTokenIssuer internalTokenIssuer = mock(InternalTokenIssuer.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final FilterChain chain = mock(FilterChain.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(BootstrapAuthFilter.class);
        logger.detachAppender(appender);
    }

    private static UserAccessClaims claims() {
        return new UserAccessClaims("1", "default", Channel.B, Set.of("super_admin"), "access", TOKEN_ID);
    }

    private BootstrapAuthFilter filter(boolean failClosed) {
        return new BootstrapAuthFilter(verifier, internalTokenIssuer, sessionRedis,
                new RevocationCheckPolicy(failClosed),
                List.of("/admin/v1/auth/tenants", "/actuator/**"));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/system/users");
        request.addHeader("Authorization", "Bearer token-001736");
        return request;
    }

    // ---------- AC-7：fail-closed=true + 撤销检查异常 → 拒绝（503 + REVOCATION_CHECK_UNAVAILABLE） ----------

    @Test
    void should_rejectWith503AndRevocationUnavailable_when_sessionRedisFailsAndFailClosedTrue() throws Exception {
        when(verifier.verifyAccess(any())).thenReturn(claims());
        when(sessionRedis.isRevoked(TOKEN_ID)).thenThrow(new IllegalStateException("redis down"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(true).doFilter(request(), response, chain);

        assertThat(response.getStatus()).as("fail-closed 拒绝：HTTP 503").isEqualTo(503);
        assertThat(response.getContentAsString())
                .as("错误码 REVOCATION_CHECK_UNAVAILABLE")
                .contains(SecurityError.REVOCATION_CHECK_UNAVAILABLE.code());
        // 拒绝时不得放行到下游
        verify(chain, never()).doFilter(any(), any());
    }

    // ---------- AC-7：默认 false + 撤销检查异常 → 放行 + WARN（现状） ----------

    @Test
    void should_continueChainWithWarnLog_when_sessionRedisFailsAndFailClosedDefault() throws Exception {
        when(verifier.verifyAccess(any())).thenReturn(claims());
        when(sessionRedis.isRevoked(TOKEN_ID)).thenThrow(new IllegalStateException("redis down"));
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        Logger logger = (Logger) LoggerFactory.getLogger(BootstrapAuthFilter.class);
        appender.start();
        logger.addAppender(appender);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(false).doFilter(request(), response, chain);

        // 默认 false 与现状完全一致：放行下游继续
        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).as("默认放行不写 503").isNotEqualTo(503);
        assertThat(appender.list).as("放行伴随 WARN 日志（消息内容不钉死）")
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    // ---------- AC-7：撤销检查可用且未撤销 → 放行（fail-closed 不误伤正常路径） ----------

    @Test
    void should_continueDownstream_when_checkSucceedsAndNotRevoked_underFailClosed() throws Exception {
        when(verifier.verifyAccess(any())).thenReturn(claims());
        when(sessionRedis.isRevoked(TOKEN_ID)).thenReturn(false);
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(true).doFilter(request(), response, chain);

        assertThat(response.getStatus()).as("检查可用未撤销：fail-closed 不误伤").isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    // ---------- AC-7 边界：无 tokenId 不查撤销（既有口径保留，fail-closed 亦不触发） ----------

    @Test
    void should_notConsultRedis_when_noTokenId_underFailClosed() throws Exception {
        when(verifier.verifyAccess(any())).thenReturn(new UserAccessClaims(
                "1", "default", Channel.B, Set.of("super_admin"), "access", null));
        when(internalTokenIssuer.issue(any())).thenReturn("internal-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(true).doFilter(request(), response, chain);

        verify(sessionRedis, never()).isRevoked(anyString());
        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
