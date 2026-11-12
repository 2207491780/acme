package com.acme.platform.auth.infrastructure.client;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认证画像网关测试（架构设计 3.3 / Q-D08：服务身份主体 + USER_NOT_FOUND 统一失败映射 FR-005）。
 */
class AuthProfileGatewayTest {

    private final AuthProfileClient client = mock(AuthProfileClient.class);

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void callsWithServiceIdentitySubject() {
        AtomicReference<String> userIdAtCall = new AtomicReference<>();
        when(client.findByUsername(anyString())).thenAnswer(invocation -> {
            UserContext context = UserContext.get();
            userIdAtCall.set(context == null ? null : context.userId());
            return new ContractResult<>(true, "0", null,
                    new AuthProfileView("1", "admin", "昵称", "default", "ENABLED", Set.of()));
        });
        new AuthProfileGateway(client).findByUsername("admin");
        // Q-D08：内部令牌 userId=服务名（经 framework-feign 主体构造链路）
        assertThat(userIdAtCall.get()).isEqualTo(AuthProfileGateway.SERVICE_IDENTITY);
        // 调用结束恢复原上下文（无主体）
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void userNotFoundMappedToUnifiedLoginFailed() {
        when(client.findByUsername(anyString())).thenThrow(new BizException(new ErrorCodeOf(
                SystemErrorCodes.USER_NOT_FOUND)));
        assertThatThrownBy(() -> new AuthProfileGateway(client).findByUsername("nobody"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("platform-auth-01-0001"));
    }

    @Test
    void otherDownstreamErrorsPropagate() {
        // 下游故障不掩盖为认证失败（可观测）
        when(client.findByUsername(anyString())).thenThrow(new BizException(new ErrorCodeOf("SYS-09-0001")));
        assertThatThrownBy(() -> new AuthProfileGateway(client).findByUsername("admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code()).isEqualTo("SYS-09-0001"));
    }

    /** 测试用错误码承载。 */
    private record ErrorCodeOf(String code) implements com.acme.common.core.ErrorCode {

        @Override
        public String defaultMessage() {
            return "下游错误";
        }
    }
}
