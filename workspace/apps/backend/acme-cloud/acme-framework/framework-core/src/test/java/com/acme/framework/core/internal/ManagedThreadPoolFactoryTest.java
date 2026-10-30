package com.acme.framework.core.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.ContextSnapshot;
import com.acme.framework.core.config.CoreProperties;
import com.acme.framework.core.config.RejectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受管线程池工厂测试（FR-001 异常分支、BR-015；架构设计 4.1）。
 */
class ManagedThreadPoolFactoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    private ThreadPoolTaskExecutor pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
        ContextSnapshot.clear();
    }

    private static CoreProperties.Executor config(int core, int max, int queue) {
        CoreProperties.Executor config = new CoreProperties.Executor();
        config.setCorePoolSize(core);
        config.setMaxPoolSize(max);
        config.setQueueCapacity(queue);
        return config;
    }

    @Test
    void defaultThreadNamePrefixApplied() throws Exception {
        pool = ManagedThreadPoolFactory.create(config(1, 1, 4));
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        pool.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("acme-core-");
    }

    @Test
    void customThreadNamePrefixApplied() throws Exception {
        CoreProperties.Executor config = config(1, 1, 4);
        config.setThreadNamePrefix("t-");
        pool = ManagedThreadPoolFactory.create(config);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        pool.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("t-");
    }

    @Test
    void defaultRejectionPolicyAborts() throws Exception {
        pool = ManagedThreadPoolFactory.create(config(1, 1, 1));
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            awaitQuietly(blocker);
        });
        assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {
        });
        assertThatThrownBy(() -> pool.execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class);
        blocker.countDown();
    }

    @Test
    void callerRunsPolicyExecutesOnSubmitter() throws Exception {
        CoreProperties.Executor config = config(1, 1, 1);
        config.setRejectionPolicy(RejectionPolicy.CALLER_RUNS);
        pool = ManagedThreadPoolFactory.create(config);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            awaitQuietly(blocker);
        });
        assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {
        });
        AtomicReference<String> executionThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        pool.execute(() -> {
            executionThread.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(executionThread.get()).isEqualTo(Thread.currentThread().getName());
        blocker.countDown();
    }

    @Test
    void uncaughtExceptionLoggedAndPoolKeepsServing() throws Exception {
        pool = ManagedThreadPoolFactory.create(config(1, 1, 4));
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedThreadPoolFactory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            pool.execute(() -> {
                throw new IllegalStateException("boom-uncaught");
            });
            // 池继续受理后续任务（FR-001 异常分支）
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(done::countDown);
            assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // UncaughtExceptionHandler 记录 ERROR 日志（BR-015、取舍 11）
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && appender.list.stream()
                    .noneMatch(event -> event.getLevel() == Level.ERROR)) {
                Thread.sleep(20);
            }
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage()).contains("boom-uncaught");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
