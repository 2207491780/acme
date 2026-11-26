package com.acme.platform.auth.infrastructure.client;

import com.acme.platform.auth.AuthError;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261125-001738 内部查档契约（platform-auth 消费方）用例测试：
 *
 * <ul>
 *   <li>AC-3 链路：{@code AuthProfileGateway} 以 (tenantCode, username) 两参数调用钉死契约
 *       {@code AuthProfileClient.findByUsername(tenantCode, username)}，path/返回形状不变。</li>
 *   <li>AC-3 失败口径：未知租户 / 域内无此用户（下游 USER_NOT_FOUND）统一映射为 LOGIN_FAILED，
 *       文案不变、不泄露差异（FR-005 / REQ-20261117-025200 既有口径）。</li>
 * </ul>
 *
 * <p>口径说明：下游失败以 {@code BizException(USER_NOT_FOUND)} 表达（{@code AuthProfileGatewayTest}
 * userNotFoundMappedToUnifiedLoginFailed 先例）；统一失败码取 {@code AuthError.LOGIN_FAILED.code()}。</p>
 */
class REQ202611251738AuthProfileTenantContractTest {

    private final AuthProfileClient client = mock(AuthProfileClient.class);

    @Test
    void should_forwardTenantCodeAndUsername_when_findByUsernameCalled() {
        when(client.findByUsername("req1738-A", "req1738-u1"))
                .thenReturn(new ContractResult<>(true, "0", null,
                        new AuthProfileView("1001", "req1738-u1", "A域", "req1738-A", "ENABLED", Set.of())));

        AuthProfileView profile = new AuthProfileGateway(client).findByUsername("req1738-A", "req1738-u1");

        assertThat(profile.tenantId()).as("返回形状不变：画像仍含归属 tenantId").isEqualTo("req1738-A");
        verify(client).findByUsername("req1738-A", "req1738-u1");
    }

    @Test
    void should_mapToUnifiedLoginFailed_when_tenantUnknownOrUserAbsentInTenant() {
        when(client.findByUsername("req1738-unknown", "req1738-u1"))
                .thenThrow(new BizException(new ErrorCodeOf(SystemErrorCodes.USER_NOT_FOUND)));
        when(client.findByUsername("req1738-A", "req1738-ghost"))
                .thenThrow(new BizException(new ErrorCodeOf(SystemErrorCodes.USER_NOT_FOUND)));

        assertThatThrownBy(() -> new AuthProfileGateway(client)
                .findByUsername("req1738-unknown", "req1738-u1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .as("未知租户应统一失败为 LOGIN_FAILED（文案不变）")
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));

        assertThatThrownBy(() -> new AuthProfileGateway(client)
                .findByUsername("req1738-A", "req1738-ghost"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .as("域内无此用户应同为 LOGIN_FAILED（同码不泄露差异）")
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }

    /** 测试用错误码承载（AuthProfileGatewayTest 同款）。 */
    private record ErrorCodeOf(String code) implements com.acme.common.core.ErrorCode {

        @Override
        public String defaultMessage() {
            return "下游用户未找到";
        }
    }
}
