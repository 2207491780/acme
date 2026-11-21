package com.acme.framework.job.interceptor;

import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.internal.TaskTriggerMode;
import com.acme.framework.job.support.JobHandler;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * {@code @JobHandler} 执行切面（REQ-20261121-200724 确认③/⑦）：本地调度入口（@Scheduled 兜底）
 * 与手动触发（TaskTrigger）走同一互斥 + 执行日志口径，语义与 {@code @AcmeTask} 切面一致；
 * 调度中心下发执行经执行器 SDK 以触发语义进入本切面（TASK_RUNNING 显式失败上报，
 * 双跑互斥由 ShedLock 保证——确认⑦「双跑幂等由保留期语义+互斥锁保证」）。
 */
@Aspect
@Order(100)
public class JobHandlerExecutionAspect {

    private final TaskRegistry registry;
    private final TaskExecutionSupport executionSupport;

    public JobHandlerExecutionAspect(TaskRegistry registry, TaskExecutionSupport executionSupport) {
        this.registry = registry;
        this.executionSupport = executionSupport;
    }

    @Around("@annotation(jobHandler)")
    public Object around(ProceedingJoinPoint joinPoint, JobHandler jobHandler) {
        TaskDefinition definition = registry.find(jobHandler.jobKey()).orElseGet(() ->
                fallbackDefinition(joinPoint, jobHandler));
        boolean triggerMode = TaskTriggerMode.isActive();
        return executionSupport.execute(definition, triggerMode,
                () -> joinPoint.proceed(joinPoint.getArgs()));
    }

    private static TaskDefinition fallbackDefinition(ProceedingJoinPoint joinPoint, JobHandler handler) {
        Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        return new TaskDefinition(handler.jobKey(), com.acme.framework.job.support.MutexMode.GLOBAL,
                null, null,
                joinPoint.getTarget(), method,
                joinPoint.getSignature().getDeclaringType().getSimpleName() + "#" + method.getName());
    }
}
