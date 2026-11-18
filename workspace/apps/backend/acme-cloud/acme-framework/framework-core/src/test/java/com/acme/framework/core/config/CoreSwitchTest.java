package com.acme.framework.core.config;

import com.acme.framework.core.internal.ApplicationContextRegistry;
import com.acme.framework.core.support.ApplicationContexts;
import com.acme.framework.core.support.CoreExecutor;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（AC-007/AC-008；FR-006、NFR-001/NFR-005）。
 */
class CoreSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoreAutoConfiguration.class));

    // ---------- AC-007（FR-006、BR-005） ----------

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.core.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CoreExecutor.class);
                    assertThat(context).doesNotHaveBean(TransactionEventPublisher.class);
                    assertThat(context).doesNotHaveBean(ApplicationContextRegistry.class);
                    assertThat(context).doesNotHaveBean(CoreProperties.class);
                });
    }

    // ---------- AC-008（Q-001、NFR-005） ----------

    @Test
    void zeroConfigStartsWithDefaultsAndFullFunction() throws Exception {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CoreExecutor.class);
            assertThat(context).hasSingleBean(TransactionEventPublisher.class);
            assertThat(context).hasSingleBean(ApplicationContextRegistry.class);

            CoreProperties properties = context.getBean(CoreProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getExecutor().getCorePoolSize()).isEqualTo(8);
            assertThat(properties.getExecutor().getMaxPoolSize()).isEqualTo(32);
            assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(1000);
            assertThat(properties.getExecutor().getKeepAliveSeconds()).isEqualTo(60);
            assertThat(properties.getExecutor().getThreadNamePrefix()).isEqualTo("acme-core-");
            assertThat(properties.getExecutor().getRejectionPolicy()).isEqualTo(RejectionPolicy.ABORT);
            assertThat(properties.getExecutor().isWaitForTasksToCompleteOnShutdown()).isTrue();
            assertThat(properties.getExecutor().getAwaitTerminationSeconds()).isEqualTo(20);

            // 功能可用：异步执行
            CoreExecutor executor = context.getBean(CoreExecutor.class);
            CountDownLatch done = new CountDownLatch(1);
            executor.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // 功能可用：ApplicationContexts 就绪
            assertThat(ApplicationContexts.getBean(CoreExecutor.class)).isSameAs(executor);

            // 功能可用：事件发布受理（投递语义见 TransactionEventPublisherTest）
            context.getBean(TransactionEventPublisher.class).publish("E");
        });
    }

    /** 宽松布尔值与 @ConfigurationProperties 绑定同源（3.5 可关闭性、Modules.md 3.5）。 */
    @Test
    void looseBooleanSwitchValuesAgreeWithPropertyBinding() {
        for (String value : List.of("true", "on", "yes", "1", "TRUE", "On")) {
            runner.withPropertyValues("acme.core.enabled=" + value)
                    .run(context -> {
                        assertThat(context).hasSingleBean(CoreExecutor.class);
                        assertThat(context.getBean(CoreProperties.class).isEnabled())
                                .as("宽松值 %s 应与属性绑定一致（true）", value)
                                .isTrue();
                        assertThat(context.getEnvironment().getProperty("acme.core.enabled", Boolean.class, Boolean.TRUE))
                                .as("宽松值 %s 应与条件读取一致（true）", value)
                                .isTrue();
                    });
        }
        for (String value : List.of("false", "off", "no", "0", "FALSE", "Off")) {
            runner.withPropertyValues("acme.core.enabled=" + value)
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(CoreExecutor.class);
                        assertThat(context).doesNotHaveBean(CoreProperties.class);
                        assertThat(context.getEnvironment().getProperty("acme.core.enabled", Boolean.class, Boolean.TRUE))
                                .as("宽松值 %s 应与条件读取一致（false）", value)
                                .isFalse();
                    });
        }
    }

    @Test
    void customExecutorConfigBinds() throws Exception {
        runner.withPropertyValues(
                        "acme.core.executor.core-pool-size=1",
                        "acme.core.executor.max-pool-size=1",
                        "acme.core.executor.queue-capacity=4",
                        "acme.core.executor.keep-alive-seconds=30",
                        "acme.core.executor.thread-name-prefix=custom-",
                        "acme.core.executor.rejection-policy=caller-runs",
                        "acme.core.executor.wait-for-tasks-to-complete-on-shutdown=false",
                        "acme.core.executor.await-termination-seconds=5")
                .run(context -> {
                    CoreProperties properties = context.getBean(CoreProperties.class);
                    assertThat(properties.getExecutor().getCorePoolSize()).isEqualTo(1);
                    assertThat(properties.getExecutor().getMaxPoolSize()).isEqualTo(1);
                    assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(4);
                    assertThat(properties.getExecutor().getKeepAliveSeconds()).isEqualTo(30);
                    assertThat(properties.getExecutor().getThreadNamePrefix()).isEqualTo("custom-");
                    assertThat(properties.getExecutor().getRejectionPolicy()).isEqualTo(RejectionPolicy.CALLER_RUNS);
                    assertThat(properties.getExecutor().isWaitForTasksToCompleteOnShutdown()).isFalse();
                    assertThat(properties.getExecutor().getAwaitTerminationSeconds()).isEqualTo(5);

                    CoreExecutor executor = context.getBean(CoreExecutor.class);
                    AtomicReference<String> threadName = new AtomicReference<>();
                    CountDownLatch done = new CountDownLatch(1);
                    executor.execute(() -> {
                        threadName.set(Thread.currentThread().getName());
                        done.countDown();
                    });
                    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(threadName.get()).startsWith("custom-");
                });
    }
}
