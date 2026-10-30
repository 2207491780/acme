package com.acme.framework.job.interceptor;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.internal.TaskTriggerMode;
import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切面 proceed 走代理链（10.2）：目标方法上 @Transactional 等其他切面经代理生效；
 * 保留返回值与带参方法；防重入由 ShedLock 非可重入语义保证。
 */
class TaskExecutionAspectTest {

    /** 目标任务 bean（带参 + 返回值）。 */
    public static class DemoTask {

        final AtomicInteger runs = new AtomicInteger();

        @AcmeTask(taskId = "demo-task", mutex = MutexMode.GLOBAL,
                lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
        public String execute(String input) {
            runs.incrementAndGet();
            return "done:" + input;
        }
    }

    /** 模拟 @Transactional 等外层切面（类比：经代理链必须被触发）。 */
    @Aspect
    public static class TxLikeAdvice {

        static final AtomicInteger entered = new AtomicInteger();

        @Around("execution(* com.acme.framework.job.interceptor.TaskExecutionAspectTest$DemoTask.*(..))")
        public Object around(ProceedingJoinPoint pjp) throws Throwable {
            entered.incrementAndGet();
            return pjp.proceed();
        }
    }

    private static LockProvider alwaysLock() {
        return lockConfig -> Optional.of(new SimpleLock() {
            @Override
            public void unlock() {
            }

            @Override
            public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
                return Optional.empty();
            }
        });
    }

    @Test
    void proceedGoesThroughProxyChainAndKeepsReturnValue() {
        TxLikeAdvice.entered.set(0);
        DemoTask target = new DemoTask();
        TaskRegistry registry = new TaskRegistry();
        TaskExecutionSupport support = new TaskExecutionSupport(
                new DefaultLockingTaskExecutor(alwaysLock()),
                new JobLockKeys(new KeyComposer("order-service")), new JobProperties());
        TaskExecutionAspect aspect = new TaskExecutionAspect(registry, support);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new TxLikeAdvice());
        factory.addAspect(aspect);
        DemoTask proxy = factory.getProxy();

        String result = proxy.execute("x");
        assertThat(result).isEqualTo("done:x");
        assertThat(target.runs).hasValue(1);
        assertThat(TxLikeAdvice.entered)
                .as("其他切面经代理链生效（10.2，不再直调目标）").hasValueGreaterThan(0);
    }

    @Test
    void triggerModeFlagReadableByAspect() {
        TaskTriggerMode.enter();
        assertThat(TaskTriggerMode.isActive()).isTrue();
        TaskTriggerMode.exit();
        assertThat(TaskTriggerMode.isActive()).isFalse();
    }
}
