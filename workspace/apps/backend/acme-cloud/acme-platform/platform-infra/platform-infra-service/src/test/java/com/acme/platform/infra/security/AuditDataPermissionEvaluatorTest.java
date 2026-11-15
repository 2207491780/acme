package com.acme.platform.infra.security;

import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.framework.security.support.DataScope;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import com.acme.platform.infra.infrastructure.client.DataScopeGateway;
import com.acme.platform.system.api.DataScopeView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计数据权限评估器测试（consumer-auth-refresh-dataperm + REQ-20261114-120104 scope 改造）：
 * 键匹配 + 权限点判定；未知键 fail-closed；scope 映射（all 权限点→ALL 档、角色档映射、
 * 角色档 ALL 无权限点收敛 SELF、解析失败 fail-closed SELF）。
 */
class AuditDataPermissionEvaluatorTest {

    private final AuthorizationGateway gateway = mock(AuthorizationGateway.class);
    private final DataScopeGateway dataScopeGateway = mock(DataScopeGateway.class);
    private final AuditDataPermissionEvaluator evaluator =
            new AuditDataPermissionEvaluator(gateway, dataScopeGateway);

    @Test
    void allowsWhenAllViewPermissionGranted() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(true);
        assertThat(evaluator.evaluate(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY)))
                .isEqualTo(DataPermissionDecision.ALLOW);
    }

    @Test
    void deniesWhenPermissionMissingOrRemoteFails() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(false);
        assertThat(evaluator.evaluate(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY)))
                .isEqualTo(DataPermissionDecision.DENY);
    }

    @Test
    void unknownKeyDeniedFailClosed() {
        assertThat(evaluator.evaluate(context("some.other.key")))
                .isEqualTo(DataPermissionDecision.DENY);
    }

    @Test
    void scopeResolvesUnrestrictedWhenAllViewPermissionGranted() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(true);
        assertThat(evaluator.resolve(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY)).unrestricted())
                .isTrue();
    }

    @Test
    void scopeMapsRoleTiersFromRemoteView() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(false);
        when(dataScopeGateway.currentDataScope()).thenReturn(new DataScopeView(
                DataScope.DEPT_AND_CHILD.name(), List.of(1L, 2L, 3L), List.of("42", "43")));
        DataScopeDecision decision = evaluator.resolve(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY));
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT_AND_CHILD);
        assertThat(decision.visibleDeptIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(decision.visibleUserIds()).containsExactlyInAnyOrder("42", "43");
    }

    @Test
    void scopeCapsRoleAllTierToSelfWithoutAllViewPermission() {
        // 行为兼容映射：角色档 ALL 但无 infra:audit:list:all → 收敛 SELF（V9 缺省列不放大存量审计视图）
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(false);
        when(dataScopeGateway.currentDataScope())
                .thenReturn(new DataScopeView(DataScope.ALL.name(), List.of(), List.of()));
        DataScopeDecision decision = evaluator.resolve(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY));
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
    }

    @Test
    void scopeFailsClosedToSelfWhenRemoteMissing() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(false);
        when(dataScopeGateway.currentDataScope()).thenReturn(null);
        DataScopeDecision decision = evaluator.resolve(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY));
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
    }

    @Test
    void scopeEmptyVisibleSetFailsClosedToSelf() {
        when(gateway.hasPermission("infra:audit:list:all")).thenReturn(false);
        when(dataScopeGateway.currentDataScope())
                .thenReturn(new DataScopeView(DataScope.CUSTOM.name(), List.of(), List.of()));
        DataScopeDecision decision = evaluator.resolve(context(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY));
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
    }

    private static DataPermissionContext context(String key) {
        return new DataPermissionContext(key, "AuditQueryUseCase.pageAll", "42", "default", Set.of("ops"));
    }
}
