package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminPasswordSetRequest;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重置密码用例测试（FR-005 / 架构设计 4.6：权限判定、密码策略、BCrypt upsert、会话撤销、失败显式响应）。
 */
class ResetPasswordUseCaseTest {

    private final AuthorizationGateway authorizationGateway = mock(AuthorizationGateway.class);
    private final CredentialRecorder credentialRecorder = mock(CredentialRecorder.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private final ResetPasswordUseCase useCase =
            new ResetPasswordUseCase(authorizationGateway, credentialRecorder, sessionRedis, encoder);

    @Test
    void permissionDeniedRejected403BeforeAnyWrite() {
        // 越权 403（platform-system-07-0001，openapi Forbidden 契约）且不做任何写
        doThrow(new BizException(new com.acme.common.core.ErrorCode() {
            @Override
            public String code() {
                return "platform-system-07-0001";
            }

            @Override
            public String defaultMessage() {
                return "无操作权限";
            }
        })).when(authorizationGateway).requireCredentialManage();
        assertThatThrownBy(() -> useCase.resetPassword(7L, new AdminPasswordSetRequest("NewPass@123")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("platform-system-07-0001"));
        verify(credentialRecorder, never()).resetCredential(anyLong(), anyString(), anyString());
    }

    @Test
    void shortPasswordRejectedPolicy400() {
        assertThatThrownBy(() -> useCase.resetPassword(7L, new AdminPasswordSetRequest("Short1!")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.PASSWORD_POLICY_VIOLATED.code()));
    }

    @Test
    void successUpsertsBcryptCredentialAndRevokesSessions() {
        when(credentialRecorder.resetCredential(eq(7L), anyString(), anyString())).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-1", Instant.now().plusSeconds(600)),
                new SessionRecorder.RevocableToken("jti-2", Instant.now().plusSeconds(60))));
        useCase.resetPassword(7L, new AdminPasswordSetRequest("NewPass@123"));

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialRecorder).resetCredential(eq(7L), hashCaptor.capture(), anyString());
        // BCrypt 不可逆散列（$2a 前缀、可校验）
        assertThat(hashCaptor.getValue()).startsWith("$2");
        assertThat(encoder.matches("NewPass@123", hashCaptor.getValue())).isTrue();
        verify(sessionRedis).revoke(eq("jti-1"), anyLong());
        verify(sessionRedis).revoke(eq("jti-2"), anyLong());
    }

    @Test
    void revokeFailureFailsExplicitly() {
        // FR-004：会话已置 KICKED 而撤销失败 → 显式失败响应（不出现「成功但可用」）
        when(credentialRecorder.resetCredential(eq(7L), anyString(), anyString())).thenReturn(List.of(
                new SessionRecorder.RevocableToken("jti-1", Instant.now().plusSeconds(600))));
        doThrow(new SessionRedis.RedisKeyWriteFailedException("jti-1", null))
                .when(sessionRedis).revoke(anyString(), anyLong());
        assertThatThrownBy(() -> useCase.resetPassword(7L, new AdminPasswordSetRequest("NewPass@123")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.common.core.CommonError.INTERNAL_ERROR.code()));
    }
}
