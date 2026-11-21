package com.acme.framework.job.executor;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.config.JobAutoConfiguration;
import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261121-200724（JOB-2）AC-6——{@code @AcmeTask} 旧任务语义不变（框架域 runner 面）：
 * 标注 {@code @Deprecated} 保留本地语义（契约确认③）——收集注册（TaskRegistry 含旧 taskId）、
 * 本地 TaskTrigger 触发照常执行、重复注册显式失败（既有语义，JOB-2 迁移不得改变）。
 *
 * <p>「不进 handlers 不接单」腿见 {@link REQ20261121200724ExecutorSdkIntegrationTest}
 * （注册请求 handlers 排除断言）；infra 服务面 sys_job_execution 双写腿见
 * platform-infra-service REQ20261121200724JobMigrationZeroBreakTest。
 * 装配照抄本模块 JobSwitchTest 先例（ApplicationContextRunner + DataSource 桩件 + KeyComposer）。</p>
 */
class REQ20261121200724AcmeTaskLegacySemanticsTest {

    static final String LEGACY_TASK_ID = "it-job2-legacy-task";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAutoConfiguration.class))
            .withUserConfiguration(DataSourceStub.class, LegacyTaskConfig.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("job2-legacy-test"));

    @Test
    void should_register_legacy_task_in_registry_when_acme_task_method_scanned() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskRegistry.class);
            TaskRegistry registry = context.getBean(TaskRegistry.class);
            assertThat(registry.find(LEGACY_TASK_ID))
                    .as("@AcmeTask 旧任务应照旧收集进 TaskRegistry（语义不变，确认③）")
                    .isPresent();
            assertThat(registry.find(LEGACY_TASK_ID).orElseThrow().taskId())
                    .isEqualTo(LEGACY_TASK_ID);
        });
    }

    @Test
    void should_execute_legacy_task_body_when_task_trigger_invoked() {
        runner.run(context -> {
            TaskTrigger trigger = context.getBean(TaskTrigger.class);
            LegacyTaskConfig.Probe probe = context.getBean(LegacyTaskConfig.Probe.class);

            Object result = trigger.trigger(LEGACY_TASK_ID, "TRIGGER_MANUAL");

            assertThat(probe.invocations).as("本地 trigger 应执行任务方法体（语义不变）")
                    .containsExactly("TRIGGER_MANUAL");
            assertThat(result).as("trigger 应返回业务返回值").isEqualTo("legacy-done");
        });
    }

    @Test
    void should_reject_duplicate_registration_when_same_task_id_registered_again() {
        runner.run(context -> {
            TaskRegistry registry = context.getBean(TaskRegistry.class);
            assertThatThrownBy(() -> registry.register(new TaskDefinition(LEGACY_TASK_ID,
                    MutexMode.GLOBAL, Duration.ZERO, Duration.ZERO,
                    new Object(), null, "it-duplicate")))
                    .as("重复注册应显式失败（既有语义 FR-026，迁移不得改变）")
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    /** 旧任务宿主：@AcmeTask 方法照旧声明（JOB-2 仅迁移 4 个保留期任务，旧语义保留）。
     *  嵌套类须 public：TaskTrigger 经反射跨包调用（package-private 会 IllegalAccessException）。 */
    @Configuration(proxyBeanMethods = false)
    public static class LegacyTaskConfig {

        @Bean
        public Probe probe() {
            return new Probe();
        }

        public static class Probe {

            final List<String> invocations = new CopyOnWriteArrayList<>();

            public String run(String triggerType) {
                invocations.add(triggerType);
                return "legacy-done";
            }
        }

        @Bean
        public LegacyTaskHost legacyTaskHost(Probe probe) {
            return new LegacyTaskHost(probe);
        }

        public static class LegacyTaskHost {

            private final Probe probe;

            LegacyTaskHost(Probe probe) {
                this.probe = probe;
            }

            @AcmeTask(taskId = LEGACY_TASK_ID)
            public String execute(String triggerType) {
                return probe.run(triggerType);
            }
        }
    }

    /** 数据源桩件（照抄 JobSwitchTest：AUTO 模式 JDBC 优先路径；不触达真实库）。 */
    @Configuration(proxyBeanMethods = false)
    static class DataSourceStub {

        @Bean
        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    DataSourceStub.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    (p, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            throw new SQLException("stub");
                        }
                        if ("unwrap".equals(method.getName())) {
                            throw new SQLException("stub");
                        }
                        if ("isWrapperFor".equals(method.getName())) {
                            return false;
                        }
                        if ("getLogWriter".equals(method.getName())) {
                            return null;
                        }
                        if ("getParentLogger".equals(method.getName())) {
                            return Logger.getAnonymousLogger();
                        }
                        return null;
                    });
        }
    }
}
