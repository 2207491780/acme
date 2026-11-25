package com.acme.common.context;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 上下文透传手工装饰门面（FR-012；REQ-20261125-001736 AC-5）：业务自建线程池在无 Spring
 * 装配（无全局 TaskDecorator bean）场景下等价透传——
 * 提交时刻 {@link ContextSnapshot#capture()} 快照，执行期恢复，结束（含异常路径）清理。
 *
 * <p>语义与 Spring 装配面 {@link ContextTaskDecorator} 一致（单源复用其 Runnable 包装）；
 * {@code ExecutorService} 装饰仅 {@code execute} 路径包装任务，其余方法直通（提交点即捕获点，
 * {@code submit}/{@code invoke*} 经 {@code execute} 均覆盖）。</p>
 */
public final class ContextExecutors {

    private ContextExecutors() {
    }

    /** 装饰无返回值任务（FR-012）。 */
    public static Runnable decorate(Runnable task) {
        return new ContextTaskDecorator().decorate(task);
    }

    /** 装饰有返回值任务（FR-012）。 */
    public static <V> Callable<V> decorate(Callable<V> task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        return () -> {
            try {
                snapshot.restore();
                return task.call();
            } finally {
                ContextSnapshot.clear();
            }
        };
    }

    /** 装饰 {@link Executor}（FR-012）。 */
    public static Executor decorate(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为空");
        }
        return task -> executor.execute(decorate(task));
    }

    /** 装饰 {@link ExecutorService}（FR-012；submit/invoke* 入口同样在提交时刻捕获）。 */
    public static ExecutorService decorate(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为空");
        }
        return new ContextPropagatingExecutorDelegator(executor);
    }

    /** 委托式装饰（仅 execute 路径包装任务，其余方法直通；提交点即捕获点）。 */
    private static final class ContextPropagatingExecutorDelegator extends AbstractExecutorService {

        private final ExecutorService delegate;

        private ContextPropagatingExecutorDelegator(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(decorate(command));
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
