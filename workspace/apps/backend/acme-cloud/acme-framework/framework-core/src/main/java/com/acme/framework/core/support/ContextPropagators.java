package com.acme.framework.core.support;

import com.acme.common.context.ContextScope;
import com.acme.common.context.ContextSnapshot;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 上下文透传装饰门面（FR-002；AC-004 可复用装饰面，Modules.md 3.3.1）。
 *
 * <p>语义：提交时刻 {@link ContextSnapshot#capture()} 快照，执行期经 {@link ContextScope} 恢复，
 * 结束（含异常与中断路径）后执行线程上下文复原（BR-011/BR-012）。</p>
 */
public final class ContextPropagators {

    private ContextPropagators() {
    }

    /** 装饰无返回值任务（FR-002）。 */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        return () -> {
            try (ContextScope scope = ContextScope.open(snapshot)) {
                task.run();
            }
        };
    }

    /** 装饰有返回值任务（FR-002）。 */
    public static <V> Callable<V> wrap(Callable<V> task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        return () -> {
            try (ContextScope scope = ContextScope.open(snapshot)) {
                return task.call();
            }
        };
    }

    /** 装饰 {@link Executor}（FR-002；业务自建线程池复用面）。 */
    public static Executor wrap(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为空");
        }
        return task -> executor.execute(wrap(task));
    }

    /** 装饰 {@link ExecutorService}（FR-002；submit/invoke* 入口同样在提交时刻捕获）。 */
    public static ExecutorService wrap(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为空");
        }
        return new ContextPropagatingExecutorDelegator(executor);
    }

    /** 委托式装饰（仅 execute 路径包装任务，其余方法直通；BR-023 承诺边界）。 */
    private static final class ContextPropagatingExecutorDelegator extends AbstractExecutorService {

        private final ExecutorService delegate;

        private ContextPropagatingExecutorDelegator(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
