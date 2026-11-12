package com.acme.framework.core.internal;

import com.acme.framework.core.config.CoreProperties;
import com.acme.framework.core.config.RejectionPolicy;
import com.acme.framework.core.support.ContextTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 受管线程池工厂（FR-001）：按 {@link CoreProperties.Executor} 配置受管池
 * （线程名前缀、拒绝策略映射、停机钩子），默认装配 {@link ContextTaskDecorator}（FR-002）。
 */
public final class ManagedThreadPoolFactory {

    private static final Logger log = LoggerFactory.getLogger(ManagedThreadPoolFactory.class);

    private ManagedThreadPoolFactory() {
    }

    /** 创建并初始化受管线程池（FR-001）。 */
    public static ThreadPoolTaskExecutor create(CoreProperties.Executor config) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setKeepAliveSeconds(config.getKeepAliveSeconds());
        CustomizableThreadFactory namedFactory = new CustomizableThreadFactory(config.getThreadNamePrefix());
        executor.setThreadFactory(runnable -> {
            Thread thread = namedFactory.newThread(runnable);
            // 无返回值任务失败经 UncaughtExceptionHandler 记 ERROR（取舍 11、BR-015）
            thread.setUncaughtExceptionHandler((failed, ex) ->
                    log.error("异步任务执行失败（BR-015）：{}", failed.getName(), ex));
            return thread;
        });
        executor.setRejectedExecutionHandler(rejectionHandler(config.getRejectionPolicy()));
        executor.setWaitForTasksToCompleteOnShutdown(config.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(config.getAwaitTerminationSeconds());
        executor.setTaskDecorator(new ContextTaskDecorator());
        executor.initialize();
        return executor;
    }

    /** 拒绝策略映射（FR-001 异常分支；默认 ABORT，BR-015）。 */
    static RejectedExecutionHandler rejectionHandler(RejectionPolicy policy) {
        RejectionPolicy effective = policy == null ? RejectionPolicy.ABORT : policy;
        return switch (effective) {
            case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
            case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
            case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
        };
    }
}
