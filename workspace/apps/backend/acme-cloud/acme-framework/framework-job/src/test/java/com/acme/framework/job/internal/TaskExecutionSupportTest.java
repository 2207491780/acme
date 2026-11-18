package com.acme.framework.job.internal;

import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.support.JobError;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.acme.framework.job.internal.TaskBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 互斥执行与执行日志（FR-025/027/028/029、AC-026/027、10.2、14.1）。
 */
class TaskExecutionSupportTest {

    static class Probe {

        int runs;
        boolean fail;

        String run(String arg) {
            runs++;
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return "ok:" + arg;
        }
    }

    /** 可编程 LockProvider 桩件（配 ShedLock 原生 DefaultLockingTaskExecutor）。 */
    static class FakeLockProvider implements LockProvider {

        boolean locked = true;
        RuntimeException failure;
        final List<String> lockNames = new ArrayList<>();

        @Override
        public java.util.Optional<SimpleLock> lock(
                net.javacrumbs.shedlock.core.LockConfiguration lockConfig) {
            if (failure != null) {
                throw failure;
            }
            lockNames.add(lockConfig.getName());
            if (!locked) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new SimpleLock() {
                @Override
                public void unlock() {
                    // 桩件无状态
                }

                @Override
                public java.util.Optional<SimpleLock> extend(
                        java.time.Duration lockAtMostFor, java.time.Duration lockAtLeastFor) {
                    return java.util.Optional.empty();
                }
            });
        }
    }

    private final FakeLockProvider executor = new FakeLockProvider();
    private final Probe probe = new Probe();
    private final Method method;
    private final TaskExecutionSupport support;

    TaskExecutionSupportTest() throws NoSuchMethodException {
        this.method = Probe.class.getDeclaredMethod("run", String.class);
        this.support = new TaskExecutionSupport(
                new net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor(executor),
                new JobLockKeys(new KeyComposer("order-service")), new JobProperties());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TraceContext.clear();
    }

    private TaskDefinition definition(MutexMode mode) {
        return new TaskDefinition("nightly", mode, Duration.ofMinutes(5), Duration.ofSeconds(1),
                probe, method, "Probe#run");
    }

    private TaskBody body() {
        return () -> method.invoke(probe, "x");
    }

    @Test
    void executesWithGlobalLockKeyAndReturnsValue() {
        Object result = support.execute(definition(MutexMode.GLOBAL), true, body());
        assertThat(result).isEqualTo("ok:x");
        assertThat(probe.runs).isEqualTo(1);
        assertThat(executor.lockNames).containsExactly("order-service:j:nightly");
    }

    @Test
    void tenantMutexCarriesTenantSegment() {
        TenantContext.bind(new TenantContext("t-9"));
        support.execute(definition(MutexMode.TENANT), true, body());
        assertThat(executor.lockNames).containsExactly("order-service:j:nightly:t:t-9");
    }

    @Test
    void tenantMutexWithoutTenantFallsBackToGlobal() {
        support.execute(definition(MutexMode.TENANT), true, body());
        assertThat(executor.lockNames).containsExactly("order-service:j:nightly");
    }

    @Test
    void triggerModeRejectsWhenLocked() {
        executor.locked = false;
        assertThatThrownBy(() -> support.execute(definition(MutexMode.GLOBAL), true, body()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(JobError.TASK_RUNNING));
    }

    @Test
    void scheduleModeSkipsWhenLocked() {
        executor.locked = false;
        assertThat(support.execute(definition(MutexMode.GLOBAL), false, body())).isNull();
        assertThat(probe.runs).isZero();
    }

    @Test
    void taskFailureRecordedAndPropagated() {
        probe.fail = true;
        assertThatThrownBy(() -> support.execute(definition(MutexMode.GLOBAL), true, body()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lockProviderFailureMapsExplicitly() {
        executor.failure = new IllegalStateException("shedlock table missing");
        assertThatThrownBy(() -> support.execute(definition(MutexMode.GLOBAL), true, body()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(JobError.LOCK_PROVIDER_UNAVAILABLE));
    }

    @Test
    void bindsTraceIdDuringExecutionAndClearsAfter() {
        TraceContext[] observed = new TraceContext[1];
        support.execute(definition(MutexMode.GLOBAL), true, () -> {
            observed[0] = TraceContext.get();
            return "done";
        });
        assertThat(observed[0]).as("执行期绑定 TraceContext（14.1）").isNotNull();
        assertThat(observed[0].traceId()).isNotBlank().isNotEqualTo("-");
        assertThat(TraceContext.get()).as("执行结束清理").isNull();
    }
}
