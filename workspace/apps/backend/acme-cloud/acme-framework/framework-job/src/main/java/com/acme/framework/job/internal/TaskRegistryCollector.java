package com.acme.framework.job.internal;

import com.acme.framework.job.support.AcmeTask;
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
 * 任务收集（internal，FR-026）：上下文刷新后扫描 {@code @AcmeTask} 方法登记注册表；
 * 以 ApplicationListener 收集（不作 BeanPostProcessor，消除提前拉取装配 bean 的启动告警）；
 * 登记代理本体（触发经代理调用走完整切面链，10.2）。
 */
public class TaskRegistryCollector implements ApplicationListener<ContextRefreshedEvent> {

    private final ObjectProvider<TaskRegistry> registry;

    public TaskRegistryCollector(ObjectProvider<TaskRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        TaskRegistry target = registry.getIfAvailable();
        if (target == null) {
            return;
        }
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
                if (task == null) {
                    continue;
                }
                target.register(new TaskDefinition(
                        task.taskId(),
                        task.mutex(),
                        parseDuration(task.lockAtMostFor()),
                        parseDuration(task.lockAtLeastFor()),
                        bean,
                        method,
                        targetClass.getSimpleName() + "#" + method.getName()));
            }
        }
    }

    private static java.time.Duration parseDuration(String iso) {
        return iso == null || iso.isBlank() ? null : java.time.Duration.parse(iso);
    }
}
