package com.acme.framework.tenant.support;

import com.acme.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户取值与无租户口径（FR-001/AC-001、架构设计 5.2；缺租户 fail-closed——拒绝下沉数据操作面：
 * 会话创建期返回未绑定哨兵（platform-services-bootstrap 修订），读空集、写被 UnboundTenantGuard 拒绝）。
 */
class TenantIdentifierResolverTest {

    private final TenantIdentifierResolver resolver = new TenantIdentifierResolver();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void resolvesFromTenantContext() {
        TenantContext.bind(new TenantContext("t-1"));
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("t-1");
    }

    @Test
    void missingTenantResolvesUnboundSentinel() {
        // 会话创建期不抛（Spring Data 启动期查询派生开临时会话）；fail-closed 下沉数据操作面
        String tenant = resolver.resolveCurrentTenantIdentifier();
        assertThat(tenant).isEqualTo(TenantIdentifierResolver.UNBOUND_TENANT);
        assertThat(resolver.isRoot(tenant)).isFalse();
    }

    @Test
    void blankTenantResolvesUnboundSentinel() {
        TenantContext.bind(new TenantContext(" "));
        assertThat(resolver.resolveCurrentTenantIdentifier())
                .isEqualTo(TenantIdentifierResolver.UNBOUND_TENANT);
    }

    @Test
    void bypassScopeResolvesRootAndIsRootRecognized() {
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            String tenant = resolver.resolveCurrentTenantIdentifier();
            assertThat(tenant).isEqualTo(TenantBypassScope.ROOT_TENANT);
            assertThat(resolver.isRoot(tenant)).isTrue();
        }
        assertThat(resolver.isRoot("t-1")).isFalse();
        assertThat(resolver.isRoot(TenantIdentifierResolver.UNBOUND_TENANT)).isFalse();
    }

    @Test
    void validateExistingSessionsDisabled() {
        assertThat(resolver.validateExistingCurrentSessions()).isFalse();
    }
}
