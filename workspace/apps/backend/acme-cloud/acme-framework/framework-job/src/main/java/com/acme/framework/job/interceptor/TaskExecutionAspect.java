package com.acme.framework.job.interceptor;

import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.internal.TaskTriggerMode;
import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * {@code @AcmeTask} 执行切面（FR-027/AC-026、10.2）：调度入口（@Scheduled 等经代理调用）走互斥 + 执行日志；
 * 切面内 {@code joinPoint.proceed()} 走代理链（保留返回值/带参方法，@Transactional 等切面生效）；
 * 手动触发经 {@code TaskTrigger} 同径（FR-028 两径一致）；{@code @AcmeTask} 防重入由 ShedLock 非可重入保证。
 */
@Aspect
@Order(100)
public class TaskExecutionAspect {

    private final TaskRegistry registry;
    private final TaskExecutionSupport executionSupport;

    public TaskExecutionAspect(TaskRegistry registry, TaskExecutionSupport executionSupport) {
        this.registry = registry;
        this.executionSupport = executionSupport;
    }

    @Around("@annotation(acmeTask)")
    public Object around(ProceedingJoinPoint joinPoint, AcmeTask acmeTask) {
        TaskDefinition definition = registry.find(acmeTask.taskId()).orElseGet(() ->
                fallbackDefinition(joinPoint, acmeTask));
        boolean triggerMode = TaskTriggerMode.isActive();
        return executionSupport.execute(definition, triggerMode,
                () -> joinPoint.proceed(joinPoint.getArgs()));
    }

    private static TaskDefinition fallbackDefinition(ProceedingJoinPoint joinPoint, AcmeTask task) {
        Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        return new TaskDefinition(task.taskId(), task.mutex(),
                parse(task.lockAtMostFor()), parse(task.lockAtLeastFor()),
                joinPoint.getTarget(), method,
                joinPoint.getSignature().getDeclaringType().getSimpleName() + "#" + method.getName());
    }

    private static java.time.Duration parse(String iso) {
        return iso == null || iso.isBlank() ? null : java.time.Duration.parse(iso);
    }
}
