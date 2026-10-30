package com.acme.framework.job.support;

import com.acme.common.core.BizException;
import com.acme.framework.job.internal.TaskTriggerMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 手动触发（FR-028/AC-026）：与调度同一互斥 + 执行日志口径（取舍 #10）；
 * 经代理调用（切面内 proceed 走代理链，10.2）；任务不存在拒绝、执行中拒绝（TASK_RUNNING，不排队）；
 * 触发入口权限归使用方（BR-017）。
 */
public class TaskTrigger {

    private final TaskRegistry registry;

    public TaskTrigger(TaskRegistry registry) {
        this.registry = registry;
    }

    /**
     * 触发一次执行（经代理走完整切面链；返回业务返回值，未获锁/失败抛显式异常）。
     *
     * @throws BizException TASK_NOT_FOUND / TASK_RUNNING / LOCK_PROVIDER_UNAVAILABLE
     */
    public Object trigger(String taskId, Object... args) {
        TaskDefinition definition = registry.find(taskId)
                .orElseThrow(() -> new BizException(JobError.TASK_NOT_FOUND, "任务不存在: " + taskId));
        TaskTriggerMode.enter();
        try {
            return invokeOnProxy(definition, args);
        } finally {
            TaskTriggerMode.exit();
        }
    }

    /** 经代理实例调用（JDK/CGLIB 均路由到方法拦截链，保证 @Transactional 等切面生效）。 */
    private static Object invokeOnProxy(TaskDefinition definition, Object[] args) {
        try {
            Method invoker = definition.target().getClass()
                    .getMethod(definition.method().getName(), definition.method().getParameterTypes());
            return invoker.invoke(definition.target(), args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("任务执行失败: " + definition.taskId(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("任务触发失败: " + definition.taskId(), ex);
        }
    }
}
