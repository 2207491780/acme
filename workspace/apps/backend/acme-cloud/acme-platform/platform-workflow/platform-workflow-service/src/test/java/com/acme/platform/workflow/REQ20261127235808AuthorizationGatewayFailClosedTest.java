package com.acme.platform.workflow;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.workflow.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-20261127-235808（B2b P1-08）workflow 域 AuthorizationGateway fail-closed 回归锁定单测——
 * 覆盖 AC-8/AC-11：权限判定异常（故障注入）→ {@code hasPermission}=false、
 * {@code requirePermission} 抛 PERMISSION_DENIED（{@code platform-system-07-0001}）+ HTTP 403
 * 信封；正常 true/false 路径一并锁定（既有 fail-closed 行为不回归）。
 *
 * <p>装配缝（委派单钉死事实 7）：单参构造注入 {@code AuthorizationClient}（四域同型副本，
 * 结构收敛=B7 边界，确认记录④，本批不改 API 形状）。与 auth 域同场景同口径（AC-11）。</p>
 */
class REQ20261127235808AuthorizationGatewayFailClosedTest {

    private final AuthorizationClient authorizationClient = mock(AuthorizationClient.class);

    private final AuthorizationGateway gateway = new AuthorizationGateway(authorizationClient);

    // ---------- 正常路径：true / false ----------

    @Test
    void hasPermissionReturnsTrueWhenClientGrants() {
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, Boolean.TRUE));

        assertThat(gateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("下游授予 → 放行").isTrue();
    }

    @Test
    void hasPermissionReturnsFalseWhenClientDenies() {
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, Boolean.FALSE));

        assertThat(gateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("下游拒绝 → 不放行").isFalse();
    }

    @Test
    void requirePermissionPassesWhenPermissionHeld() {
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, Boolean.TRUE));

        assertThatCode(() -> gateway.requirePermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("正常授权路径 requirePermission 不抛").doesNotThrowAnyException();
    }

    // ---------- 故障注入：异常 → fail-closed（既有行为回归锁定） ----------

    @Test
    void hasPermissionFailsClosedWhenAuthorizationClientFails() {
        when(authorizationClient.hasPermission(anyString()))
                .thenThrow(new RuntimeException("simulated authorization outage"));

        assertThat(gateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("下游异常 → false（fail-closed，异常不穿透）").isFalse();
    }

    @Test
    void requirePermissionThrowsPermissionDenied403WhenAuthorizationClientFails() {
        when(authorizationClient.hasPermission(anyString()))
                .thenThrow(new RuntimeException("simulated authorization outage"));

        assertThatThrownBy(() -> gateway.requirePermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    ErrorCode errorCode = ((BizException) ex).errorCode();
                    assertThat(errorCode.code())
                            .as("统一 PERMISSION_DENIED 契约（非 500）")
                            .isEqualTo(SystemErrorCodes.PERMISSION_DENIED);
                    assertThat(errorCode)
                            .as("错误码承载 HTTP 403（ErrorCode+HttpStatusAware 双接口）")
                            .isInstanceOf(HttpStatusAware.class);
                    assertThat(((HttpStatusAware) errorCode).httpStatus())
                            .as("HTTP 403 权限语义").isEqualTo(403);
                });
    }

    // ---------- 正常拒绝路径：requirePermission 统一 403 契约（回归锁定） ----------

    @Test
    void requirePermissionThrowsPermissionDenied403WhenPermissionNotHeld() {
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, Boolean.FALSE));

        assertThatThrownBy(() -> gateway.requirePermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("正常拒绝语义不变：同 PERMISSION_DENIED + 403 信封")
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    ErrorCode errorCode = ((BizException) ex).errorCode();
                    assertThat(errorCode.code()).isEqualTo(SystemErrorCodes.PERMISSION_DENIED);
                    assertThat(((HttpStatusAware) errorCode).httpStatus()).isEqualTo(403);
                });
    }
}
