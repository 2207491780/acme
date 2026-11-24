package com.acme.framework.job.internal;

import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.JobHandler;
import com.acme.framework.job.support.JobHandlerDefinition;
import com.acme.framework.job.support.JobHandlerRegistry;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Method;

/**
 * 任务收集（internal，FR-026 + REQ-20261121-200724 确认③）：上下文刷新后扫描 {@code @AcmeTask} /
 * {@code @JobHandler} 方法登记注册表——{@code @AcmeTask} 仅本地语义；{@code @JobHandler} 额外登记
 * {@link JobHandlerRegistry}（执行器清单 + 下发定位）并写 {@link TaskRegistry} 兼容视图
 * （本地 TaskTrigger 触发消费）；
 * 以 ApplicationListener 收集（不作 BeanPostProcessor，消除提前拉取装配 bean 的启动告警）；
 * 登记代理本体（触发经代理调用走完整切面链，10.2）。
 */
public class TaskRegistryCollector implements ApplicationListener<ContextRefreshedEvent> {

    private final ObjectProvider<TaskRegistry> registry;
    private final ObjectProvider<JobHandlerRegistry> handlerRegistry;

    public TaskRegistryCollector(ObjectProvider<TaskRegistry> registry,
                                 ObjectProvider<JobHandlerRegistry> handlerRegistry) {
        this.registry = registry;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        TaskRegistry target = registry.getIfAvailable();
        if (target == null) {
            return;
        }
        JobHandlerRegistry handlers = handlerRegistry.getIfAvailable();
        if (!(event.getApplicationContext() instanceof ConfigurableApplicationContext cac)
                || !(cac.getBeanFactory() instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.getBeanDefinition(beanName).getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                continue;
            }
            Object bean;
            try {
                bean = beanFactory.getBean(beanName);
            } catch (RuntimeException ex) {
                continue;
            }
            if (bean == null) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                AcmeTask task = method.getAnnotation(AcmeTask.class);
                JobHandler handler = method.getAnnotation(JobHandler.class);
                if (task != null && handler != null) {
                    throw new IllegalStateException(
                            "同一方法不得同时标注 @AcmeTask 与 @JobHandler（REQ-20261121-200724 确认③）: "
                                    + targetClass.getSimpleName() + "#" + method.getName());
                }
                if (task != null) {
                    target.register(new TaskDefinition(
                            task.taskId(),
                            task.mutex(),
                            parseDuration(task.lockAtMostFor()),
                            parseDuration(task.lockAtLeastFor()),
                            bean,
                            method,
                            targetClass.getSimpleName() + "#" + method.getName()));
                }
                if (handler != null && handlers != null) {
                    handlers.register(new JobHandlerDefinition(
                            handler.jobKey(),
                            handler.timeoutMs(),
                            handler.shardable(),
                            handler.description(),
                            bean,
                            method,
                            targetClass.getSimpleName() + "#" + method.getName()));
                    // 兼容视图（确认③）：TaskTrigger 本地触发等本地消费面零破坏；
                    // 互斥口径与旧 @AcmeTask 相同（GLOBAL + 默认锁持有窗口），经 JobHandlerExecutionAspect 生效
                    target.register(new TaskDefinition(
                            handler.jobKey(),
                            MutexMode.GLOBAL,
                            null,
                            null,
                            bean,
                            method,
                            targetClass.getSimpleName() + "#" + method.getName()));
                }
            }
        }
    }

    private static java.time.Duration parseDuration(String iso) {
        return iso == null || iso.isBlank() ? null : java.time.Duration.parse(iso);
    }
}
