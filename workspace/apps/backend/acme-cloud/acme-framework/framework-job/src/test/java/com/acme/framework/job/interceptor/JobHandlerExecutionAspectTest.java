package com.acme.framework.job.interceptor;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.internal.TaskExecutionSupport;
import com.acme.framework.job.support.JobHandler;
import com.acme.framework.job.support.JobHandlerRegistry;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
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
 * {@code @JobHandler} 执行切面（确认③/⑦）：本地触发经代理链走互斥 + 执行日志，语义与
 * {@code @AcmeTask} 切面一致；其他切面经代理链生效（10.2）；带参与返回值保留。
 */
class JobHandlerExecutionAspectTest {

    /** 目标任务 bean（迁移后形态：String triggerType 参数）。 */
    public static class DemoHandler {

        final AtomicInteger runs = new AtomicInteger();

        @JobHandler(jobKey = "demo-handler", timeoutMs = 5000)
        public String execute(String triggerType) {
            runs.incrementAndGet();
            return "done:" + triggerType;
        }
    }

    /** 模拟 @Transactional 等外层切面（经代理链必须被触发）。 */
    @Aspect
    public static class TxLikeAdvice {

        static final AtomicInteger entered = new AtomicInteger();

        @Around("execution(* com.acme.framework.job.interceptor.JobHandlerExecutionAspectTest$DemoHandler.*(..))")
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
    void localTriggerGoesThroughProxyChainAndKeepsReturnValue() {
        TxLikeAdvice.entered.set(0);
        DemoHandler target = new DemoHandler();
        TaskExecutionSupport support = new TaskExecutionSupport(
                new DefaultLockingTaskExecutor(alwaysLock()),
                new JobLockKeys(new KeyComposer("order-service")), new JobProperties());
        JobHandlerExecutionAspect aspect = new JobHandlerExecutionAspect(new TaskRegistry(), support);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new TxLikeAdvice());
        factory.addAspect(aspect);
        DemoHandler proxy = factory.getProxy();
        // 注册代理本体（收集器登记代理 bean 同口径：触发经代理调用走完整切面链，10.2）
        TaskRegistry registry = new TaskRegistry();
        registry.register(new TaskDefinition("demo-handler", null, null, null, proxy,
                methodOf(target), "DemoHandler#execute"));

        TaskTrigger trigger = new TaskTrigger(registry);
        Object result = trigger.trigger("demo-handler", "MANUAL");

        assertThat(result).isEqualTo("done:MANUAL");
        assertThat(target.runs).hasValue(1);
        assertThat(TxLikeAdvice.entered)
                .as("其他切面经代理链生效（10.2，本地触发语义不变，AC-6/AC-8）").hasValueGreaterThan(0);
    }

    private static java.lang.reflect.Method methodOf(DemoHandler target) {
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (method.getAnnotation(JobHandler.class) != null) {
                return method;
            }
        }
        throw new IllegalStateException("@JobHandler 方法缺失");
    }

    @Test
    void registryCollectsDefinition() {
        JobHandlerRegistry handlers = new JobHandlerRegistry();
        DemoHandler target = new DemoHandler();
        handlers.register(new com.acme.framework.job.support.JobHandlerDefinition(
                "demo-handler", 5000, false, "demo", target, methodOf(target), "DemoHandler#execute"));
        assertThat(handlers.keys()).containsExactly("demo-handler");
    }
}
