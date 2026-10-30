package com.acme.framework.job.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.interceptor.TaskExecutionAspect;
import com.acme.framework.job.internal.LockProviderBridges;
import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.internal.TaskRegistryCollector;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

/**
 * framework-job 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * ShedLock 双模式装配（FR-025/AC-025）；锁表归使用方 schema（BR-019、Modules.md 3.5 数据边界）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("job")
@ConditionalOnClass(LockProvider.class)
@EnableConfigurationProperties(JobProperties.class)
public class JobAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 任务锁 key 规范（FR-029）。 */
    @Bean
    public JobLockKeys jobLockKeys(KeyComposer keyComposer) {
        return new JobLockKeys(keyComposer);
    }

    /** 任务注册表（FR-026；ROLE_INFRASTRUCTURE 防提前拉取告警）。 */
    @Bean
    public TaskRegistry taskRegistry() {
        TaskRegistry registry = new TaskRegistry();
        return registry;
    }

    /** @AcmeTask 收集（FR-026；ApplicationListener 收集，消除 BPP 提前初始化告警）。 */
    @Bean
    public static ApplicationListener<org.springframework.context.event.ContextRefreshedEvent> taskRegistryCollector(
            ObjectProvider<TaskRegistry> registry) {
        return new TaskRegistryCollector(registry);
    }

    /** ShedLock 锁提供方（FR-025：JDBC/Redis/AUTO）。 */
    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider shedLockProvider(JobProperties properties,
                                         ObjectProvider<DataSource> dataSource,
                                         ObjectProvider<StringRedisTemplate> redisTemplate) {
        return LockProviderBridges.resolve(properties, dataSource, redisTemplate);
    }

    /** 编程式互斥（FR-025、取舍 #10：LockingTaskExecutor 统一入口）。 */
    @Bean
    @ConditionalOnMissingBean(LockingTaskExecutor.class)
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }

    /** 任务互斥执行与执行日志（FR-027/028/029）。 */
    @Bean
    public TaskExecutionSupport taskExecutionSupport(LockingTaskExecutor lockingTaskExecutor,
                                                     JobLockKeys jobLockKeys, JobProperties properties) {
        return new TaskExecutionSupport(lockingTaskExecutor, jobLockKeys, properties);
    }

    /** 手动触发（FR-028）。 */
    @Bean
    public TaskTrigger taskTrigger(TaskRegistry taskRegistry) {
        return new TaskTrigger(taskRegistry);
    }

    /** 调度入口切面（FR-027/AC-026、10.2 proceed 走代理链）。 */
    @Bean
    public TaskExecutionAspect taskExecutionAspect(TaskRegistry taskRegistry,
                                                   TaskExecutionSupport executionSupport) {
        return new TaskExecutionAspect(taskRegistry, executionSupport);
    }
}
