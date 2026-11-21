package com.acme.framework.job.config;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.executor.DispatchCommand;
import com.acme.framework.job.executor.ExecutorLifecycle;
import com.acme.framework.job.executor.JobExecutorRunController;
import com.acme.framework.job.executor.SchedulerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器 SDK 开关与降级缺省（REQ-20261121-200724 确认④/AC-5）：
 * enabled=false 纯本地零装配；默认 true 装配，LB 缺席时静默降级不接单。
 */
class ExecutorSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAutoConfiguration.class))
            .withBean(KeyComposer.class, () -> new KeyComposer("job2-executor-switch-test"))
            // 无 DataSource/Redis 宿主：FR-025 缺失显式失败，注入内存锁桩让上下文可装配
            .withBean(net.javacrumbs.shedlock.core.LockProvider.class,
                    ExecutorSwitchTest::alwaysLock);

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

    @Test
    void executorDisabledAssemblesNothing() {
        runner.withPropertyValues("acme.job.executor.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(JobProperties.class);
            assertThat(context).doesNotHaveBean(SchedulerClient.class);
            assertThat(context).doesNotHaveBean(ExecutorLifecycle.class);
            assertThat(context).doesNotHaveBean(JobExecutorRunController.class);
            // 本地语义不受影响（单体纯本地模式，AC-5）
            assertThat(context).hasSingleBean(com.acme.framework.job.support.TaskTrigger.class);
        });
    }

    @Test
    void executorDefaultsEnabled() {
        runner.run(context -> {
            JobProperties.Executor executor = context.getBean(JobProperties.class).getExecutor();
            assertThat(executor.isEnabled()).isTrue();
            assertThat(executor.getHeartbeatIntervalMs()).isEqualTo(10_000L);
            assertThat(executor.getSchedulerServiceName()).isEqualTo("platform-scheduler");
            assertThat(context).hasSingleBean(SchedulerClient.class);
            assertThat(context).hasSingleBean(ExecutorLifecycle.class);
            assertThat(context).hasSingleBean(JobExecutorRunController.class);
        });
    }

    @Test
    void heartbeatIntervalConfigurable() {
        runner.withPropertyValues("acme.job.executor.heartbeat-interval-ms=5000").run(context ->
                assertThat(context.getBean(JobProperties.class).getExecutor().getHeartbeatIntervalMs())
                        .isEqualTo(5000L));
    }

    @Test
    void degradedLifecycleRejectsDispatch() {
        runner.run(context -> {
            ExecutorLifecycle lifecycle = context.getBean(ExecutorLifecycle.class);
            // LB bean 缺席（纯本地形态）：未注册即不接单（AC-4/AC-5 缺省口径）
            assertThat(lifecycle.accepting()).isFalse();
            assertThat(lifecycle.appName()).isBlank();
            JobExecutorRunController controller = context.getBean(JobExecutorRunController.class);
            DispatchCommand command = new DispatchCommand("unknown-job", "unknown-job", null,
                    new DispatchCommand.Shard(0, 1), null, "t-1", 0);
            assertThat(controller.run(1L, command).accepted()).isFalse();
        });
    }
}
