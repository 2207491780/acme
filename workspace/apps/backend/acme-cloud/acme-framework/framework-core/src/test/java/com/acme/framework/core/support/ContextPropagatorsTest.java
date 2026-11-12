package com.acme.framework.core.support;

import com.acme.common.context.ContextSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可复用装饰面测试（AC-004：非本模块线程池上满足 AC-001/AC-003 断言）。
 */
class ContextPropagatorsTest {

    private static final long TIMEOUT_SECONDS = 5;

    private ExecutorService foreign;

    @BeforeEach
    void setUp() {
        foreign = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        foreign.shutdownNow();
        ContextSnapshot.clear();
    }

    @Test
    void wrappedRunnableOnForeignExecutorPropagatesAndRestores() throws Exception {
        foreign.submit(() -> {
            TestContexts.bind("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        TestContexts.bind("foreign", Locale.SIMPLIFIED_CHINESE);
        Runnable decorated = ContextPropagators.wrap(() ->
                TestContexts.assertContext("foreign", Locale.SIMPLIFIED_CHINESE));
        ContextSnapshot.clear();

        foreign.submit(decorated).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // AC-003：执行线程原上下文不变
        foreign.submit(() -> {
            TestContexts.assertContext("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void wrappedCallableOnForeignExecutorRestoresAfterFailure() throws Exception {
        foreign.submit(() -> {
            TestContexts.bind("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        TestContexts.bind("foreign2", Locale.US);
        Callable<Void> decorated = ContextPropagators.wrap(() -> {
            TestContexts.assertContext("foreign2", Locale.US);
            throw new IllegalStateException("boom");
        });
        ContextSnapshot.clear();

        Future<Void> future = foreign.submit(decorated);
        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);

        foreign.submit(() -> {
            TestContexts.assertContext("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void wrappedExecutorDecoratesExecute() throws Exception {
        TestContexts.bind("wrapExec", Locale.US);
        Executor decorated = ContextPropagators.wrap((Executor) foreign);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // 装饰在 execute 调用时捕获快照（提交时刻），故提交完成后再清理
        decorated.execute(() -> {
            try {
                TestContexts.assertContext("wrapExec", Locale.US);
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                done.countDown();
            }
        });
        ContextSnapshot.clear();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }

    @Test
    void wrappedExecutorServiceDecoratesSubmit() throws Exception {
        TestContexts.bind("wrapEs", Locale.SIMPLIFIED_CHINESE);
        ExecutorService decorated = ContextPropagators.wrap(foreign);

        Future<?> future = decorated.submit((Runnable) () ->
                TestContexts.assertContext("wrapEs", Locale.SIMPLIFIED_CHINESE));
        ContextSnapshot.clear();
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(decorated.isShutdown()).isFalse();
    }

    @Test
    void wrapRejectsNullArguments() {
        assertThatThrownBy(() -> ContextPropagators.wrap((Runnable) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextPropagators.wrap((Callable<String>) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextPropagators.wrap((Executor) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextPropagators.wrap((ExecutorService) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
