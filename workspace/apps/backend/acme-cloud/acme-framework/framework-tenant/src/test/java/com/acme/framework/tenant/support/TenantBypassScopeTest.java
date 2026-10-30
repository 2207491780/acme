package com.acme.framework.tenant.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旁路作用域（FR-003、架构设计 5.4：try-with-resources、可重入、异常路径清理）。
 */
class TenantBypassScopeTest {

    @Test
    void openAndCloseTogglesActive() {
        assertThat(TenantBypassScope.isActive()).isFalse();
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            assertThat(TenantBypassScope.isActive()).isTrue();
        }
        assertThat(TenantBypassScope.isActive()).isFalse();
    }

    @Test
    void reentrantScopeKeepsActiveUntilOuterCloses() {
        try (TenantBypassScope outer = TenantBypassScope.open("test")) {
            try (TenantBypassScope inner = TenantBypassScope.open("test")) {
                assertThat(TenantBypassScope.isActive()).isTrue();
            }
            assertThat(TenantBypassScope.isActive()).as("内层关闭后外层仍旁路").isTrue();
        }
        assertThat(TenantBypassScope.isActive()).isFalse();
    }

    @Test
    void exceptionPathStillCloses() {
        try {
            try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException ignored) {
            // 预期异常
        }
        assertThat(TenantBypassScope.isActive()).isFalse();
    }
}
