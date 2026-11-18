package com.acme.framework.core.support;

import com.acme.common.context.ContextSnapshot;
import com.acme.common.context.UserContext;
import com.acme.framework.core.config.CoreProperties;
import com.acme.framework.core.internal.ManagedThreadPoolFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异步上下文透传测试（AC-001/002/003/020；P1 验收一）。
 */
class ContextPropagationTest {

    private static final long TIMEOUT_SECONDS = 5;

    private CoreExecutor coreExecutor;

    @BeforeEach
    void setUp() {
        coreExecutor = newExecutor(2);
    }

    @AfterEach
    void tearDown() {
        coreExecutor.shutdown();
        ContextSnapshot.clear();
    }

    private static CoreExecutor newExecutor(int threads) {
        CoreProperties.Executor config = new CoreProperties.Executor();
        config.setCorePoolSize(threads);
        config.setMaxPoolSize(threads);
        config.setQueueCapacity(16);
        return new CoreExecutor(ManagedThreadPoolFactory.create(config));
    }

    @Test
    void submitCallableCarriesSubmitTimeContext() throws Exception {
        TestContexts.bind("ac1c", Locale.US);
        Future<String> future = coreExecutor.submit(() -> {
            TestContexts.assertContext("ac1c", Locale.US);
            return "ok";
        });
        assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    void executeRunnableCarriesSubmitTimeContext() throws Exception {
        TestContexts.bind("ac1e", Locale.SIMPLIFIED_CHINESE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        coreExecutor.execute(() -> {
            try {
                TestContexts.assertContext("ac1e", Locale.SIMPLIFIED_CHINESE);
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }

    @Test
    void submitRunnableCarriesSubmitTimeContext() throws Exception {
        TestContexts.bind("ac1s", Locale.US);
        Future<?> future = coreExecutor.submit((Runnable) () -> TestContexts.assertContext("ac1s", Locale.US));
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void reusedThreadDoesNotLeakPreviousSnapshot() throws Exception {
        CoreExecutor single = newExecutor(1);
        try {
            TestContexts.bind("taskA", Locale.US);
            single.submit(() -> {
                TestContexts.assertContext("taskA", Locale.US);
                return null;
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ContextSnapshot.clear();
            TestContexts.bind("taskB", Locale.SIMPLIFIED_CHINESE);
            single.submit(() -> {
                TestContexts.assertContext("taskB", Locale.SIMPLIFIED_CHINESE);
                assertThat(UserContext.get().userId()).isNotEqualTo("user-taskA");
                return null;
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            single.shutdown();
        }
    }

    @Test
    void normalCompletionRestoresPriorThreadContext() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            preloadPriorContext(worker);
            TestContexts.bind("snap", Locale.SIMPLIFIED_CHINESE);
            Callable<Void> decorated = ContextPropagators.wrap(() -> {
                TestContexts.assertContext("snap", Locale.SIMPLIFIED_CHINESE);
                return null;
            });
            ContextSnapshot.clear();
            worker.submit(decorated).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertPriorContextPreserved(worker);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void exceptionPathRestoresPriorThreadContext() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            preloadPriorContext(worker);
            TestContexts.bind("snap", Locale.SIMPLIFIED_CHINESE);
            Callable<Void> decorated = ContextPropagators.wrap(() -> {
                throw new IllegalStateException("boom");
            });
            ContextSnapshot.clear();
            Future<Void> future = worker.submit(decorated);
            assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertPriorContextPreserved(worker);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void interruptionPathRestoresPriorThreadContext() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            preloadPriorContext(worker);
            TestContexts.bind("snap", Locale.SIMPLIFIED_CHINESE);
            Callable<Void> decorated = ContextPropagators.wrap(() -> {
                throw new InterruptedException("interrupted");
            });
            ContextSnapshot.clear();
            Future<Void> future = worker.submit(decorated);
            assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InterruptedException.class);
            assertPriorContextPreserved(worker);
        } finally {
            worker.shutdownNow();
        }
    }

    /** 预置执行线程原上下文 P（BR-012 恢复基准）。 */
    private void preloadPriorContext(ExecutorService worker) throws Exception {
        worker.submit(() -> {
            TestContexts.bind("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void assertPriorContextPreserved(ExecutorService worker) throws Exception {
        worker.submit(() -> {
            TestContexts.assertContext("prior", Locale.US);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
