package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.SelfSessionView;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自助账号安全用例单元测试（REQ-20261119-082531 AC-3/AC-4 单元层同语义，纯 JUnit 无 Spring，
 * 模式照 {@code ResetPasswordUseCaseTest} 先例）：
 * <ul>
 *   <li>AC-3 改密链：旧密码错误 OLD_PASSWORD_INVALID 且密码未变、新旧相同 SAME_PASSWORD、
 *       新密码违反 8-64 策略 PASSWORD_POLICY_VIOLATED；成功路径 upsert BCrypt 凭据＋
 *       仅踢其他会话（保留当前 tokenId）＋撤销集写入；撤销失败显式失败（FR-004）。</li>
 *   <li>AC-4 会话面：列表仅本人 ACTIVE 且 current 标记正确；单会话下线仅本人可达
 *       （他人会话 NOT_FOUND 语义防探测）；下线其他保留当前。</li>
 * </ul>
 */
class SelfAccountUseCaseTest {

    private static final Long USER_ID = 7L;
    private static final String CURRENT_TOKEN = "bearer-current-token";
    private static final String CURRENT_JTI = "jti-current";

    private final AuthCredentialRepository credentialRepository = mock(AuthCredentialRepository.class);
    private final CredentialRecorder credentialRecorder = mock(CredentialRecorder.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final UserTokenIssuer tokenIssuer = mock(UserTokenIssuer.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private final SelfAccountUseCase useCase = new SelfAccountUseCase(credentialRepository,
            credentialRecorder, sessionRecorder, sessionRedis, tokenIssuer, encoder);

    private void stubCurrentToken() {
        when(tokenIssuer.verify(CURRENT_TOKEN)).thenReturn(new UserTokenIssuer.VerifiedToken(
                CURRENT_JTI, "access", "B", Instant.now().plusSeconds(600)));
    }

    private void stubCredential(String password) {
        when(credentialRepository.findByUserId(USER_ID))
                .thenReturn(new AuthCredential(USER_ID, "default", encoder.encode(password)));
    }

    // ---------- AC-3：旧密码错误 → OLD_PASSWORD_INVALID 且密码未变 ----------

    @Test
    void putMePasswordWithWrongOldPasswordReturnsOldPasswordInvalidAndPasswordUnchanged() {
        stubCurrentToken();
        stubCredential("OldPass@123");
        assertThatThrownBy(() -> useCase.changePassword(USER_ID, CURRENT_TOKEN,
                "WrongPass@123", "NewPass@456"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.OLD_PASSWORD_INVALID.code()));
        // 密码未变：不触发凭据 upsert、不写撤销集
        verify(credentialRecorder, never()).resetCredential(anyLong(), anyString(), anyString(), anyString());
        verify(sessionRedis, never()).revoke(anyString(), anyLong());
    }

    @Test
    void putMePasswordWithCorrectOldPasswordReturnsVoidAndKeepsCurrentSession() {
        stubCurrentToken();
        stubCredential("OldPass@123");
        when(credentialRecorder.resetCredential(eq(USER_ID), anyString(), anyString(), eq(CURRENT_JTI)))
                .thenReturn(List.of(new SessionRecorder.RevocableToken("jti-other",
                        Instant.now().plusSeconds(600))));

        useCase.changePassword(USER_ID, CURRENT_TOKEN, "OldPass@123", "NewPass@456");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        // 保留当前会话：keepTokenId=当前 jti（改密后当前登录态保持，确认记录③）
        verify(credentialRecorder).resetCredential(eq(USER_ID), hashCaptor.capture(),
                anyString(), eq(CURRENT_JTI));
        assertThat(hashCaptor.getValue()).startsWith("$2");
        assertThat(encoder.matches("NewPass@456", hashCaptor.getValue())).isTrue();
        // 仅其他会话进撤销集；当前令牌不撤销
        verify(sessionRedis).revoke(eq("jti-other"), anyLong());
        verify(sessionRedis, never()).revoke(eq(CURRENT_JTI), anyLong());
    }

    @Test
    void putMePasswordRejectsSameAsOldPassword() {
        stubCurrentToken();
        stubCredential("OldPass@123");
        assertThatThrownBy(() -> useCase.changePassword(USER_ID, CURRENT_TOKEN,
                "OldPass@123", "OldPass@123"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.SAME_PASSWORD.code()));
        verify(credentialRecorder, never()).resetCredential(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void putMePasswordRejectsNewPasswordViolatingPolicy8To64() {
        stubCurrentToken();
        stubCredential("OldPass@123");
        assertThatThrownBy(() -> useCase.changePassword(USER_ID, CURRENT_TOKEN,
                "OldPass@123", "Ab1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.PASSWORD_POLICY_VIOLATED.code()));
        verify(credentialRecorder, never()).resetCredential(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void revokeFailureFailsExplicitly() {
        // FR-004：底账已 KICKED 而撤销集写入失败 → 显式失败响应（不得「成功但令牌仍可用」）
        stubCurrentToken();
        stubCredential("OldPass@123");
        when(credentialRecorder.resetCredential(eq(USER_ID), anyString(), anyString(), eq(CURRENT_JTI)))
                .thenReturn(List.of(new SessionRecorder.RevocableToken("jti-other",
                        Instant.now().plusSeconds(600))));
        doThrow(new SessionRedis.RedisKeyWriteFailedException("jti-other", null))
                .when(sessionRedis).revoke(anyString(), anyLong());
        assertThatThrownBy(() -> useCase.changePassword(USER_ID, CURRENT_TOKEN,
                "OldPass@123", "NewPass@456"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(CommonError.INTERNAL_ERROR.code()));
    }

    // ---------- AC-4：会话列表 current 标记 ----------

    @Test
    void getMeSessionsListsOnlyOwnActiveWithCurrentMarked() {
        stubCurrentToken();
        Instant issuedAt = Instant.now().minusSeconds(600);
        Instant expiresAt = Instant.now().plusSeconds(600);
        when(sessionRecorder.activeByUserId(USER_ID)).thenReturn(List.of(
                new AuthSession(CURRENT_JTI, USER_ID, "default", "B", issuedAt, expiresAt),
                new AuthSession("jti-other", USER_ID, "default", "B", issuedAt, expiresAt)));
        when(sessionRedis.readSessionInfo(CURRENT_JTI))
                .thenReturn(Optional.of(new SessionRedis.SessionInfo("10.0.0.1", Instant.now())));
        when(sessionRedis.readSessionInfo("jti-other")).thenReturn(Optional.empty());

        List<SelfSessionView> views = useCase.listMySessions(USER_ID, CURRENT_TOKEN);

        assertThat(views).hasSize(2);
        assertThat(views.stream().filter(SelfSessionView::current).map(SelfSessionView::tokenId))
                .as("仅当前令牌对应行标记 current").containsExactly(CURRENT_JTI);
        assertThat(views.get(0).ip()).as("会话信息存在时取 ip").isEqualTo("10.0.0.1");
        assertThat(views.get(1).ip()).as("会话信息缺失 ip 为 null").isNull();
        assertThat(views.get(1).lastActiveAt()).as("缺失回退签发时间").isEqualTo(issuedAt);
    }

    // ---------- AC-4：单会话下线——目标 KICKED+撤销、当前保留；他人会话 NOT_FOUND ----------

    @Test
    void deleteMeSessionByIdKicksTargetAndRevokesWhileCurrentSurvives() {
        stubCurrentToken();
        Instant expiresAt = Instant.now().plusSeconds(600);
        AuthSession target = new AuthSession("jti-other", USER_ID, "default", "B",
                Instant.now().minusSeconds(600), expiresAt);
        when(sessionRecorder.kickableByTokenId("jti-other")).thenReturn(Optional.of(target));

        useCase.kickMySession(USER_ID, "jti-other");

        verify(sessionRecorder).kick(target);
        verify(sessionRedis).revoke(eq("jti-other"), anyLong());
        // 当前会话不受影响（未进撤销集）
        verify(sessionRedis, never()).revoke(eq(CURRENT_JTI), anyLong());
    }

    @Test
    void deleteOtherUsersSessionReturnsNotFoundSemantics() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        AuthSession others = new AuthSession("jti-victim", 9L, "default", "B",
                Instant.now().minusSeconds(600), expiresAt);
        when(sessionRecorder.kickableByTokenId("jti-victim")).thenReturn(Optional.of(others));
        // 非本人会话：与不存在同语义（SESSION_NOT_FOUND，防探测——确认记录④）
        assertThatThrownBy(() -> useCase.kickMySession(USER_ID, "jti-victim"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.SESSION_NOT_FOUND.code()));
        verify(sessionRecorder, never()).kick(any());
        verify(sessionRedis, never()).revoke(anyString(), anyLong());
    }

    @Test
    void deleteMeSessionsOthersKicksOthersKeepsCurrent() {
        stubCurrentToken();
        when(sessionRecorder.kickActiveSessionsExcept(USER_ID, CURRENT_JTI)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-other", Instant.now().plusSeconds(600))));

        useCase.kickMyOtherSessions(USER_ID, CURRENT_TOKEN);

        verify(sessionRecorder).kickActiveSessionsExcept(USER_ID, CURRENT_JTI);
        verify(sessionRedis).revoke(eq("jti-other"), anyLong());
        verify(sessionRedis, never()).revoke(eq(CURRENT_JTI), anyLong());
    }
}
