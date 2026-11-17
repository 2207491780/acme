package com.acme.platform.auth.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.BatchKickView;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthConsumerAccountRepository;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.SystemPermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线用户批量下线单元测试（REQ-20261117-061017 确认⑥，AC-7 用例面；纯 JUnit + Mockito）：
 * 非超管会话全 kick、超管会话跳过计数、未知 username 空集语义、竞态消失跳过不阻断。
 * 端到端（撤销集生效）由 PG+Redis 集成测试覆盖。
 */
class OnlineSessionUseCaseTest {

    private AuthorizationGateway authorizationGateway;
    private SessionRecorder sessionRecorder;
    private SessionRedis sessionRedis;
    private AuthProfileGateway authProfileGateway;
    private AuthConsumerAccountRepository accountRepository;
    private OnlineSessionUseCase useCase;

    @BeforeEach
    void setUp() {
        authorizationGateway = mock(AuthorizationGateway.class);
        sessionRecorder = mock(SessionRecorder.class);
        sessionRedis = mock(SessionRedis.class);
        authProfileGateway = mock(AuthProfileGateway.class);
        accountRepository = mock(AuthConsumerAccountRepository.class);
        useCase = new OnlineSessionUseCase(authorizationGateway, sessionRecorder, sessionRedis,
                authProfileGateway, accountRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void operator(String userId, String... roles) {
        UserContext.bind(new UserContext(userId, Set.of(roles)));
    }

    private void profiles(Map<Long, AuthProfileView> profileMap) {
        when(authProfileGateway.findByUserIds(anyCollection())).thenReturn(profileMap);
    }

    private static AuthProfileView profile(String userId, String username, String... roles) {
        return new AuthProfileView(userId, username, username, "default", "ENABLED", Set.of(roles));
    }

    private static AuthSession session(String tokenId, long userId) {
        return new AuthSession(tokenId, userId, "default", "B",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800));
    }

    /** 非超管用户名：全部 ACTIVE 会话逐个 kick（撤销集写入），计数 kicked（AC-7）。 */
    @Test
    void should_kickAllSessionsAndCountKicked_when_usernameNotSuperadmin() {
        operator("1", "admin");
        profiles(Map.of(3L, profile("3", "demo_operator", "operator")));
        List<AuthSession> sessions = List.of(session("op-1", 3L), session("op-2", 3L));
        when(sessionRecorder.activeNotExpired()).thenReturn(sessions);

        BatchKickView view = useCase.kickAllByUsername("demo_operator");

        assertThat(view.kickedCount()).isEqualTo(2L);
        assertThat(view.skippedCount()).isZero();
        verify(sessionRecorder).kick(sessions.get(0));
        verify(sessionRecorder).kick(sessions.get(1));
        org.mockito.Mockito.verify(sessionRedis, org.mockito.Mockito.times(2))
                .revoke(anyString(), anyLong());
        verify(authorizationGateway).requirePermission(SystemPermissions.AUTH_ONLINE_REVOKE);
    }

    /** 超管用户名 + 非超管操作者：会话全跳过并计数 skipped，不写底账/撤销集（AC-7）。 */
    @Test
    void should_skipAllSessionsAndCountSkipped_when_usernameSuperadminAndOperatorNot() {
        operator("3", "operator");
        profiles(Map.of(1L, profile("1", "admin", "super_admin")));
        List<AuthSession> sessions = List.of(session("sa-1", 1L), session("sa-2", 1L));
        when(sessionRecorder.activeNotExpired()).thenReturn(sessions);

        BatchKickView view = useCase.kickAllByUsername("admin");

        assertThat(view.kickedCount()).isZero();
        assertThat(view.skippedCount()).isEqualTo(2L);
        verify(sessionRecorder, never()).kick(any());
        verify(sessionRedis, never()).revoke(anyString(), anyLong());
    }

    /** 超管操作者：超管互踢允许（kick 语义延续，确认⑥超管保护仅约束非超管操作者）。 */
    @Test
    void should_kickSuperadminSessions_when_operatorIsSuperadmin() {
        operator("1", "super_admin");
        profiles(Map.of(1L, profile("1", "admin", "super_admin")));
        List<AuthSession> sessions = List.of(session("sa-1", 1L));
        when(sessionRecorder.activeNotExpired()).thenReturn(sessions);

        BatchKickView view = useCase.kickAllByUsername("admin");

        assertThat(view.kickedCount()).isEqualTo(1L);
        verify(sessionRecorder).kick(sessions.get(0));
    }

    /** 未知 username：空集语义 0/0（确认⑥，不报 USER_NOT_FOUND）。 */
    @Test
    void should_returnZeroCounts_when_usernameHasNoActiveSession() {
        operator("1", "admin");
        profiles(Map.of(3L, profile("3", "demo_operator", "operator")));
        when(sessionRecorder.activeNotExpired()).thenReturn(List.of(session("op-1", 3L)));

        BatchKickView view = useCase.kickAllByUsername("ghost-user");

        assertThat(view.kickedCount()).isZero();
        assertThat(view.skippedCount()).isZero();
        verify(sessionRecorder, never()).kick(any());
    }

    /** 竞态消失（kick 时已终态/过期 → SESSION_NOT_FOUND）：跳过计数，批内其余会话不阻断（确认⑥）。 */
    @Test
    void should_skipVanishedSession_andContinueBatch_when_kickableCheckFails() {
        operator("1", "admin");
        profiles(Map.of(3L, profile("3", "demo_operator", "operator")));
        AuthSession vanished = session("op-1", 3L);
        AuthSession healthy = session("op-2", 3L);
        when(sessionRecorder.activeNotExpired()).thenReturn(List.of(vanished, healthy));
        // kickActive 内无 kickable 复核（底账先置 KICKED），以 sessionRecorder.kick 抛 SESSION_NOT_FOUND 模拟竞态
        org.mockito.Mockito.doThrow(new BizException(AuthError.SESSION_NOT_FOUND))
                .when(sessionRecorder).kick(vanished);

        BatchKickView view = useCase.kickAllByUsername("demo_operator");

        assertThat(view.kickedCount()).isEqualTo(1L);
        assertThat(view.skippedCount()).isEqualTo(1L);
        verify(sessionRecorder).kick(healthy);
    }

    /** 撤销集写入失败：显式失败（INTERNAL_ERROR），与单会话 kick 同口径（不静默当成功）。 */
    @Test
    void should_failExplicitly_when_revocationWriteFails() {
        operator("1", "admin");
        profiles(Map.of(3L, profile("3", "demo_operator", "operator")));
        AuthSession target = session("op-1", 3L);
        when(sessionRecorder.activeNotExpired()).thenReturn(List.of(target));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(sessionRedis).revoke(anyString(), anyLong());

        assertThatThrownBy(() -> useCase.kickAllByUsername("demo_operator"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(com.acme.common.core.CommonError.INTERNAL_ERROR));
    }

    /** 单会话 kick 回归护栏：既有语义（SESSION_NOT_FOUND / 超管保护）不变。 */
    @Test
    void should_keepSingleKickSemantics_when_targetMissing() {
        operator("1", "admin");
        when(sessionRecorder.kickableByTokenId("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.kick("no-such"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(AuthError.SESSION_NOT_FOUND));
    }
}
