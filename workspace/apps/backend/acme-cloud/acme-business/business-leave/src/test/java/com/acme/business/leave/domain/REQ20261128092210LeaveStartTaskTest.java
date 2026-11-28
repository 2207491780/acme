package com.acme.business.leave.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-092210 P1-03 补偿任务失败登记策略单测（委派单事实 9）：attempts+1、指数退避
 * （1s→2s→4s→8s→16s，base×2^(attempts-1)）、达尝试上限 EXHAUSTED（next_retry_at 清空）、
 * last_error 留痕（超宽截断对齐 varchar(1024)）。
 */
class REQ20261128092210LeaveStartTaskTest {

    private static LeaveStartTask task(Integer attempts) {
        LeaveStartTask task = new LeaveStartTask();
        task.setId(100L);
        task.setTenantId("default");
        task.setLeaveId(42L);
        task.setStatus(LeaveStartTaskStatus.RUNNING);
        task.setAttempts(attempts);
        return task;
    }

    @Test
    void registerFailureFollowsExponentialBackoffScheduleUntilExhausted() {
        // 缺省策略（base=1000ms，上限 5 次尝试）：失败 1..4 回 PENDING 退避 1s→2s→4s→8s，失败 5 → EXHAUSTED
        LeaveStartTask task = task(null);
        Instant now = Instant.parse("2026-11-28T00:00:00Z");

        task.registerFailure(now, 1_000L, 5, "boom-1");
        assertThat(task.getStatus()).as("失败 1：PENDING +1s").isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getNextRetryAt()).isEqualTo(now.plusMillis(1_000));
        assertThat(task.getLastError()).isEqualTo("boom-1");

        task.registerFailure(now, 1_000L, 5, "boom-2");
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getNextRetryAt()).isEqualTo(now.plusMillis(2_000));

        task.registerFailure(now, 1_000L, 5, "boom-3");
        assertThat(task.getNextRetryAt()).as("失败 3：+4s").isEqualTo(now.plusMillis(4_000));

        task.registerFailure(now, 1_000L, 5, "boom-4");
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(4);
        assertThat(task.getNextRetryAt()).as("失败 4：+8s").isEqualTo(now.plusMillis(8_000));

        task.registerFailure(now, 1_000L, 5, "boom-5");
        assertThat(task.getStatus()).as("失败 5 达上限：EXHAUSTED（AC-5）")
                .isEqualTo(LeaveStartTaskStatus.EXHAUSTED);
        assertThat(task.getAttempts()).isEqualTo(5);
        assertThat(task.getNextRetryAt()).as("耗尽不再调度").isNull();
        assertThat(task.getLastError()).as("last_error 留痕至末次失败").isEqualTo("boom-5");
    }

    @Test
    void registerFailureHonorsConfiguredBackoffBaseAndMaxAttempts() {
        // 配置面（acme.leave.start-retry-backoff-base-ms / max-attempts）：base=500、上限=3
        LeaveStartTask task = task(0);
        Instant now = Instant.parse("2026-11-28T00:00:00Z");

        task.registerFailure(now, 500L, 3, "boom-1");
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getNextRetryAt()).isEqualTo(now.plusMillis(500));

        task.registerFailure(now, 500L, 3, "boom-2");
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getNextRetryAt()).isEqualTo(now.plusMillis(1_000));

        task.registerFailure(now, 500L, 3, "boom-3");
        assertThat(task.getStatus()).as("配置上限=3：第 3 次失败即耗尽")
                .isEqualTo(LeaveStartTaskStatus.EXHAUSTED);
        assertThat(task.getAttempts()).isEqualTo(3);
        assertThat(task.getNextRetryAt()).isNull();
    }

    @Test
    void registerFailureTruncatesLastErrorToColumnWidth() {
        LeaveStartTask task = task(0);
        String oversized = "x".repeat(2_000);

        task.registerFailure(Instant.now(), 1_000L, 5, oversized);

        assertThat(task.getLastError())
                .as("last_error 截断对齐 varchar(1024)")
                .hasSize(1024)
                .startsWith("x");
    }

    @Test
    void registerFailureKeepsNullLastErrorForNullException() {
        LeaveStartTask task = task(0);

        task.registerFailure(Instant.now(), 1_000L, 5, null);

        assertThat(task.getLastError()).isNull();
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
    }
}
