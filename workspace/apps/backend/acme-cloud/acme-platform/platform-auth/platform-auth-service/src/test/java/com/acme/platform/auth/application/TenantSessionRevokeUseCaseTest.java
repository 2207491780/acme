package com.acme.platform.auth.application;

import com.acme.platform.auth.api.TenantSessionRevokeView;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户会话批量撤销用例单元测试（REQ-20261117-025200 确认记录①②，纯 JUnit 无 Spring）：
 * AC-1（计数 2/0）、AC-2（单个撤销集写入失败计 failedCount 并 WARN 继续不中断——降级口径）、
 * 未知租户（无活跃会话）幂等 0/0。
 */
class TenantSessionRevokeUseCaseTest {

    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final TenantSessionRevokeUseCase useCase = new TenantSessionRevokeUseCase(sessionRecorder, sessionRedis);

    @Test
    void should_revokeAllAndCountTwo_when_tenantHasTwoActiveSessions() {
        // AC-1：租户 T 有 2 个 ACTIVE 会话 → 底账 KICKED（记录器事务承担）+ 撤销集逐个写入，计数 2/0
        when(sessionRecorder.kickTenantActiveSessions("req025200-ta")).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-1", Instant.now().plusSeconds(600)),
                new SessionRecorder.RevocableToken("jti-2", Instant.now().plusSeconds(600))));

        TenantSessionRevokeView view = useCase.revokeByTenant("req025200-ta");

        assertThat(view.revokedCount()).isEqualTo(2);
        assertThat(view.failedCount()).isZero();
        verify(sessionRedis).revoke(eq("jti-1"), anyLong());
        verify(sessionRedis).revoke(eq("jti-2"), anyLong());
    }

    @Test
    void should_countFailureAndContinue_when_singleRevokeWriteThrows() {
        // AC-2：单个撤销集写入失败 → failedCount 计数 + 继续（不中断、不回滚底账，确认记录①降级口径）
        when(sessionRecorder.kickTenantActiveSessions("req025200-ta")).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-bad", Instant.now().plusSeconds(600)),
                new SessionRecorder.RevocableToken("jti-good", Instant.now().plusSeconds(600))));
        doThrow(new SessionRedis.RedisKeyWriteFailedException("jti-bad", null))
                .when(sessionRedis).revoke(eq("jti-bad"), anyLong());

        TenantSessionRevokeView view = useCase.revokeByTenant("req025200-ta");

        assertThat(view.revokedCount()).isEqualTo(1);
        assertThat(view.failedCount()).isEqualTo(1);
        verify(sessionRedis).revoke(eq("jti-good"), anyLong());
    }

    @Test
    void should_returnEmptyCounts_when_tenantUnknown() {
        // unknown tenant → 0/0 成功返回（契约 §API 幂等）
        when(sessionRecorder.kickTenantActiveSessions("req025200-none")).thenReturn(List.of());

        TenantSessionRevokeView view = useCase.revokeByTenant("req025200-none");

        assertThat(view.revokedCount()).isZero();
        assertThat(view.failedCount()).isZero();
        verify(sessionRedis, org.mockito.Mockito.times(0)).revoke(anyString(), anyLong());
    }

    @Test
    void should_skipExpiredToken_when_remainingTtlNonPositive() {
        // 查询面已过滤未过期（防御式双查）：剩余有效期非正的令牌不写撤销集，不计失败
        when(sessionRecorder.kickTenantActiveSessions("req025200-ta")).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-expired", Instant.now().minusSeconds(1))));

        TenantSessionRevokeView view = useCase.revokeByTenant("req025200-ta");

        assertThat(view.revokedCount()).isZero();
        assertThat(view.failedCount()).isZero();
        verify(sessionRedis, org.mockito.Mockito.times(0)).revoke(anyString(), anyLong());
    }
}
