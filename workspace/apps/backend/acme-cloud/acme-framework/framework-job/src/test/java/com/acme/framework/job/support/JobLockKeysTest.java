package com.acme.framework.job.support;

import com.acme.framework.core.support.KeyComposer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务锁 key 规范（FR-029/AC-027：服务前缀 + 任务标识 + 可选租户段）。
 */
class JobLockKeysTest {

    private final JobLockKeys keys = new JobLockKeys(new KeyComposer("order-service"));

    @Test
    void globalMutexKeyHasNoTenantSegment() {
        assertThat(keys.build("daily-report", null)).isEqualTo("order-service:j:daily-report");
        assertThat(keys.build("daily-report", " ")).isEqualTo("order-service:j:daily-report");
    }

    @Test
    void tenantMutexKeyCarriesTenantSegment() {
        assertThat(keys.build("daily-report", "t-1")).isEqualTo("order-service:j:daily-report:t:t-1");
    }

    @Test
    void blankTaskIdRejected() {
        assertThatThrownBy(() -> keys.build(" ", "t-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
