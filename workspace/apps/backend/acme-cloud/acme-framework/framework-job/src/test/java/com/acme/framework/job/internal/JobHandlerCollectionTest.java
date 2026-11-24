package com.acme.framework.job.internal;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.config.JobAutoConfiguration;
import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.JobHandler;
import com.acme.framework.job.support.JobHandlerRegistry;
import com.acme.framework.job.support.TaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @JobHandler} 收集面（REQ-20261121-200724 确认③）：执行器清单登记 + TaskRegistry 兼容视图
 * （本地触发消费）+ 同方法双注解冲突显式失败 + jobKey 冲突显式失败。
 */
class JobHandlerCollectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAutoConfiguration.class))
            .withBean(KeyComposer.class, () -> new KeyComposer("job2-collection-test"))
            // 无 DataSource/Redis 宿主：FR-025 缺失显式失败，注入内存锁桩让上下文可装配
            .withBean(net.javacrumbs.shedlock.core.LockProvider.class,
                    JobHandlerCollectionTest::alwaysLock);

    @Test
    void jobHandlerRegisteredIntoHandlerRegistryAndCompatView() {
        runner.withUserConfiguration(SingleHandlerApp.class).run(context -> {
            assertThat(context).hasSingleBean(JobHandlerRegistry.class);
            JobHandlerRegistry handlers = context.getBean(JobHandlerRegistry.class);
            assertThat(handlers.keys()).containsExactly("demo-handler");
            assertThat(handlers.find("demo-handler")).isPresent();
            assertThat(handlers.find("demo-handler").get().timeoutMs()).isEqualTo(5000L);
            assertThat(handlers.find("demo-handler").get().shardable()).isFalse();
            assertThat(handlers.find("demo-handler").get().description()).isEqualTo("demo 描述");
            // 兼容视图（确认③）：TaskRegistry 同键登记，本地 TaskTrigger/列表零破坏
            TaskRegistry tasks = context.getBean(TaskRegistry.class);
            assertThat(tasks.find("demo-handler")).isPresent();
            assertThat(tasks.all()).hasSize(1);
        });
    }

    @Test
    void acmeTaskStillCollectedBesideJobHandler() {
        runner.withUserConfiguration(MixedApp.class).run(context -> {
            assertThat(context.getBean(JobHandlerRegistry.class).keys()).containsExactly("handler-a");
            TaskRegistry tasks = context.getBean(TaskRegistry.class);
            assertThat(tasks.find("handler-a")).isPresent();
            assertThat(tasks.find("legacy-task")).isPresent();
            assertThat(tasks.all()).hasSize(2);
        });
    }

    @Test
    void bothAnnotationsOnSameMethodFailsFast() {
        runner.withUserConfiguration(ConflictApp.class).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void duplicateJobKeyAcrossBeansFailsFast() {
        runner.withUserConfiguration(DuplicateApp.One.class, DuplicateApp.Two.class).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void handlersListSortedByJobKey() {
        runner.withUserConfiguration(MultiHandlerApp.class).run(context ->
                assertThat(context.getBean(JobHandlerRegistry.class).keys())
                        .containsExactly("handler-a", "handler-b"));
    }

    /** 内存锁桩（无数据源宿主；真实服务面由使用方按 AUTO/JDBC/REDIS 装配）。 */
    static net.javacrumbs.shedlock.core.LockProvider alwaysLock() {
        return lockConfig -> java.util.Optional.of(new net.javacrumbs.shedlock.core.SimpleLock() {
            @Override
            public void unlock() {
            }

            @Override
            public java.util.Optional<net.javacrumbs.shedlock.core.SimpleLock> extend(
                    java.time.Duration lockAtMostFor, java.time.Duration lockAtLeastFor) {
                return java.util.Optional.empty();
            }
        });
    }

    /** 宿主注解任务 bean 直接以配置类承载（避免自工厂方法产生同型双 bean 干扰收集计数）。 */
    @Configuration(proxyBeanMethods = false)
    static class SingleHandlerApp {

        @JobHandler(jobKey = "demo-handler", timeoutMs = 5000, description = "demo 描述")
        public Long execute(String triggerType) {
            return 1L;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MixedApp {

        @JobHandler(jobKey = "handler-a")
        public Long execute(String triggerType) {
            return 1L;
        }

        @AcmeTask(taskId = "legacy-task")
        public void legacy() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConflictApp {

        @AcmeTask(taskId = "same-key")
        @JobHandler(jobKey = "same-key")
        public Long execute(String triggerType) {
            return 1L;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateApp {

        @Configuration(proxyBeanMethods = false)
        static class One {

            @JobHandler(jobKey = "same-key")
            public Long execute(String triggerType) {
                return 1L;
            }
        }

        @Configuration(proxyBeanMethods = false)
        static class Two {

            @JobHandler(jobKey = "same-key")
            public Long execute(String triggerType) {
                return 1L;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MultiHandlerApp {

        @JobHandler(jobKey = "handler-b")
        public Long executeB(String triggerType) {
            return 2L;
        }

        @JobHandler(jobKey = "handler-a")
        public Long executeA(String triggerType) {
            return 1L;
        }
    }
}
