package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户旁路深度信号测试（FR-003；REQ-20261125-001736 AC-4）：push/pop 深度计数、归零移除、
 * isActive 只读判定。业务写入口归 framework-tenant TenantBypassScope（12.4.2），本测试直推状态。
 */
class TenantBypassSignalTest {

    @AfterEach
    void cleanup() {
        // 测试直推状态（未经 TenantBypassScope），清理对齐 push/pop 对
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
    }

    @Test
    void should_beInactiveByDefault() {
        assertThat(TenantBypassSignal.isActive()).isFalse();
        assertThat(TenantBypassSignal.depth()).isZero();
    }

    @Test
    void should_countReentrantDepth_andRemoveAtZero() {
        TenantBypassSignal.push();
        assertThat(TenantBypassSignal.isActive()).isTrue();
        assertThat(TenantBypassSignal.depth()).isEqualTo(1);

        TenantBypassSignal.push();
        assertThat(TenantBypassSignal.isActive()).isTrue();
        assertThat(TenantBypassSignal.depth()).isEqualTo(2);

        TenantBypassSignal.pop();
        assertThat(TenantBypassSignal.isActive()).isTrue();

        TenantBypassSignal.pop();
        assertThat(TenantBypassSignal.isActive()).isFalse();
        assertThat(TenantBypassSignal.depth()).isZero();
    }
}
