package com.acme.platform.infra.security;

import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计数据权限评估器测试（consumer-auth-refresh-dataperm）：键匹配 + 权限点判定；未知键 fail-closed。
 */
class AuditDataPermissionEvaluatorTest {

    private final AuthorizationGateway gateway = mock(AuthorizationGateway.class);
    private final AuditDataPermissionEvaluator evaluator = new AuditDataPermissionEvaluator(gateway);

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

    private static DataPermissionContext context(String key) {
        return new DataPermissionContext(key, "AuditQueryUseCase.pageAll", "42", "default", Set.of("ops"));
    }
}
