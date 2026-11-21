package com.acme.framework.job.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.executor.ExecutorIdentity;
import com.acme.framework.job.executor.ExecutorLifecycle;
import com.acme.framework.job.executor.JobExecutorRunController;
import com.acme.framework.job.executor.SchedulerClient;
import com.acme.framework.job.interceptor.JobHandlerExecutionAspect;
import com.acme.framework.job.interceptor.TaskExecutionAspect;
import com.acme.framework.job.internal.LockProviderBridges;
import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.internal.TaskRegistryCollector;
import com.acme.framework.job.support.JobHandlerRegistry;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.framework.security.support.InternalTokenIssuer;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;

/**
 * framework-job 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * ShedLock 双模式装配（FR-025/AC-025）；锁表归使用方 schema（BR-019、Modules.md 3.5 数据边界）。
 * 执行器 SDK（REQ-20261121-200724 确认③④⑤）：@JobHandler 注册表面 + 执行器生命周期/接单端点，
 * 经 acme.job.executor.enabled 开关（默认 true；单体 application-monolith.yml 显式 false=纯本地模式）
 * 与 LoadBalancerClient/RestClient 类在场双重条件装配——纯本地形态（无 spring-cloud）仅保留本地语义。
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

    /** 调度任务注册表（确认③：@JobHandler 执行器面事实源——handlers 清单与下发定位）。 */
    @Bean
    public JobHandlerRegistry jobHandlerRegistry() {
        return new JobHandlerRegistry();
    }

    /** @AcmeTask/@JobHandler 收集（FR-026 + 确认③；ApplicationListener 收集，消除 BPP 提前初始化告警）。 */
    @Bean
    public static ApplicationListener<org.springframework.context.event.ContextRefreshedEvent> taskRegistryCollector(
            ObjectProvider<TaskRegistry> registry, ObjectProvider<JobHandlerRegistry> handlerRegistry) {
        return new TaskRegistryCollector(registry, handlerRegistry);
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

    /** 调度任务执行切面（确认③/⑦：@JobHandler 本地兜底与手动触发同径互斥）。 */
    @Bean
    public JobHandlerExecutionAspect jobHandlerExecutionAspect(TaskRegistry taskRegistry,
                                                               TaskExecutionSupport executionSupport) {
        return new JobHandlerExecutionAspect(taskRegistry, executionSupport);
    }

    // ---------- 执行器 SDK（REQ-20261121-200724 确认④⑤；enabled=false 或无 spring-cloud 时不装配） ----------

    /** 调度中心出站客户端（注册/心跳/结果上报；LB/令牌签发缺席容忍——运行期静默降级）。 */
    @Bean
    @ConditionalOnClass({RestClient.class, LoadBalancerClient.class})
    @ConditionalOnProperty(prefix = "acme.job.executor", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public SchedulerClient jobSchedulerClient(JobProperties properties, Environment environment,
                                              ObjectProvider<LoadBalancerClient> loadBalancer,
                                              ObjectProvider<InternalTokenIssuer> tokenIssuer) {
        return new SchedulerClient(loadBalancer.getIfAvailable(), tokenIssuer.getIfAvailable(), properties,
                () -> ExecutorIdentity.appName(properties, environment));
    }

    /** 执行器生命周期（注册/心跳/降级/恢复重注册，AC-1/AC-4）。 */
    @Bean
    @ConditionalOnClass({RestClient.class, LoadBalancerClient.class})
    @ConditionalOnProperty(prefix = "acme.job.executor", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ExecutorLifecycle executorLifecycle(JobProperties properties, JobHandlerRegistry handlerRegistry,
                                               SchedulerClient schedulerClient, Environment environment) {
        return new ExecutorLifecycle(properties, handlerRegistry, schedulerClient, environment);
    }

    /** 指令接收端点（接单/异步执行/超时看门狗/结果上报，AC-2/AC-3）。 */
    @Bean
    @ConditionalOnClass({RestClient.class, LoadBalancerClient.class})
    @ConditionalOnProperty(prefix = "acme.job.executor", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public JobExecutorRunController jobExecutorRunController(ExecutorLifecycle lifecycle,
                                                             JobHandlerRegistry handlerRegistry,
                                                             TaskTrigger taskTrigger,
                                                             SchedulerClient schedulerClient,
                                                             JobProperties properties) {
        return new JobExecutorRunController(lifecycle, handlerRegistry, taskTrigger, schedulerClient, properties);
    }
}
