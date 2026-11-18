package com.acme.framework.tenant.internal;

import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Flex 租户标识工厂测试（FR-001/002、AC-001；缺租户 fail-closed、旁路语义）。
 */
class FlexTenantFactoryTest {

    private final FlexTenantFactory factory = new FlexTenantFactory();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void resolvesTenantFromContext() {
        TenantContext.bind(new TenantContext(" t-100 "));
        assertThat(factory.getTenantIds()).containsExactly("t-100");
    }

    @Test
    void unboundContextReturnsFailClosedSentinel() {
        assertThat(factory.getTenantIds()).containsExactly(FlexTenantFactory.UNBOUND_TENANT);
    }

    @Test
    void blankTenantIdReturnsFailClosedSentinel() {
        TenantContext.bind(new TenantContext("   "));
        assertThat(factory.getTenantIds()).containsExactly(FlexTenantFactory.UNBOUND_TENANT);
    }

    @Test
    void bypassScopeIgnoresTenantCondition() {
        TenantContext.bind(new TenantContext("t-100"));
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            // 官方语义：返回 null = 忽略租户条件（读全租户、写入保留实体自设值）
            assertThat(factory.getTenantIds()).isNull();
        }
        assertThat(factory.getTenantIds()).containsExactly("t-100");
    }
}
