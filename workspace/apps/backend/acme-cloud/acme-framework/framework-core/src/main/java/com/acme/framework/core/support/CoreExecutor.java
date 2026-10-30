package com.acme.framework.core.support;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * 受管异步任务执行门面（FR-001；BR-010 命名约束）。仅显式提交入口（BR-023）。
 */
public class CoreExecutor {

    private final ThreadPoolTaskExecutor executor;

    public CoreExecutor(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** 提交无返回值任务（FR-001）；失败经线程 UncaughtExceptionHandler 记录（取舍 11、BR-015）。 */
    public void execute(Runnable task) {
        executor.execute(task);
    }

    /** 提交无返回值任务并取得 Future（FR-001）。 */
    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /** 提交有返回值任务（FR-001）；失败原因经 Future.get 由提交方取得（BR-015）。 */
    public <V> Future<V> submit(Callable<V> task) {
        return executor.submit(task);
    }

    /** 停机钩子（取舍 10；wait/await 参数见 CoreProperties）。 */
    public void shutdown() {
        executor.shutdown();
    }
}
