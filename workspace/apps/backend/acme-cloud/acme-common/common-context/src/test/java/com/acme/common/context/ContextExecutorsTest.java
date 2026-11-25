package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文透传手工装饰门面测试（FR-012；REQ-20261125-001736 AC-5）：Executor/ExecutorService/
 * Runnable/Callable 四入口等价透传，无上下文场景行为不变。
 */
class ContextExecutorsTest {

    @AfterEach
    void cleanup() {
        ContextSnapshot.clear();
    }

    @Test
    void should_propagateContext_when_decoratedExecutorSubmitsTask() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));
        UserContext.bind(new UserContext("u-1", null));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Executor decorated = ContextExecutors.decorate(pool);
            decorated.execute(() -> {
                assertThat(TenantContext.get()).extracting(TenantContext::tenantId).isEqualTo("t-1");
                assertThat(UserContext.get()).extracting(UserContext::userId).isEqualTo("u-1");
            });
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_propagateContext_when_decoratedExecutorServiceSubmitsCallable() throws Exception {
        TenantContext.bind(new TenantContext("t-2"));
        ExecutorService raw = Executors.newSingleThreadExecutor();
        try (ExecutorService decorated = ContextExecutors.decorate(raw)) {
            Callable<String> task = () -> TenantContext.get().tenantId();
            assertThat(decorated.submit(task).get()).isEqualTo("t-2");
            // 任务结束清理：池线程不残留（AC-5）——worker 残留态须经未装饰 raw 提交观察
            // （经装饰提交会在提交时刻重新捕获提交线程上下文，观察不到池线程残留）
            raw.submit(() -> assertThat(TenantContext.get()).isNull()).get();
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_propagateContext_when_decoratedRunnableRunsOnWorkerThread() throws Exception {
        TraceContext.bind(new TraceContext("tr-1", "span-1"));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(ContextExecutors.decorate(
                    () -> assertThat(TraceContext.get()).extracting(TraceContext::traceId).isEqualTo("tr-1"))).get();
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_behaveUnchanged_when_noContextBound() {
        Runnable wrapped = ContextExecutors.decorate(() -> assertThat(UserContext.get()).isNull());
        wrapped.run();
    }

    @Test
    void should_rejectNullInputs() {
        assertThatThrownBy(() -> ContextExecutors.decorate((Runnable) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextExecutors.decorate((Callable<String>) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextExecutors.decorate((Executor) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextExecutors.decorate((ExecutorService) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
