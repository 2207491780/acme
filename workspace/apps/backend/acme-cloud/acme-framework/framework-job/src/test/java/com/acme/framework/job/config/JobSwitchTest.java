package com.acme.framework.job.config;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.support.JobLockKeys;
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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022）。
 */
class JobSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAutoConfiguration.class))
            .withUserConfiguration(DataSourceStub.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.job.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TaskRegistry.class);
                    assertThat(context).doesNotHaveBean(TaskTrigger.class);
                    assertThat(context).doesNotHaveBean(TaskExecutionSupport.class);
                    assertThat(context).doesNotHaveBean(JobProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            JobProperties properties = context.getBean(JobProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getShedlock().getMode()).isEqualTo(ShedLockMode.AUTO);
            assertThat(properties.getLockAtMostFor()).isEqualTo(java.time.Duration.ofMinutes(30));
            assertThat(properties.getLockAtLeastFor()).isEqualTo(java.time.Duration.ofMinutes(1));
            assertThat(context).hasSingleBean(TaskTrigger.class);
            assertThat(context).hasSingleBean(JobLockKeys.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.job.shedlock.mode=JDBC",
                        "acme.job.lock-at-most-for=PT10M",
                        "acme.job.lock-at-least-for=PT30S")
                .run(context -> {
                    JobProperties properties = context.getBean(JobProperties.class);
                    assertThat(properties.getShedlock().getMode()).isEqualTo(ShedLockMode.JDBC);
                    assertThat(properties.getLockAtMostFor()).isEqualTo(java.time.Duration.ofMinutes(10));
                    assertThat(properties.getLockAtLeastFor()).isEqualTo(java.time.Duration.ofSeconds(30));
                });
    }

    /** 数据源桩件（AUTO 模式 JDBC 优先路径；不触达真实库）。 */
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
