package com.acme.common.context;

/**
 * 上下文透传任务装饰器（FR-012；REQ-20261125-001736 AC-5）：提交时刻
 * {@link ContextSnapshot#capture()} 快照，执行期恢复快照，结束（含异常路径）清理，
 * 保证 {@code @Async}/{@code @Scheduled} 线程池线程不残留上下文（Modules.md 2.4.3）。
 *
 * <p>契约形态与 Spring {@code org.springframework.core.task.TaskDecorator} 单方法
 * {@code decorate(Runnable)} 完全一致（lambda 可直替）；common-* 依赖白名单
 * （acme-common enforcer bannedDependencies，Backend.md 7.1.2）禁止 spring 依赖，
 * 故 TaskDecorator 接口实现与全局 bean 注册位于 framework-core
 * （support.ContextTaskDecorator + CoreAutoConfiguration，{@code @ConditionalOnMissingBean}
 * 可被应用覆盖）；手工线程池经 {@link ContextExecutors} 等价透传。</p>
 */
public class ContextTaskDecorator {

    public Runnable decorate(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable 不能为空");
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        return () -> {
            try {
                snapshot.restore();
                runnable.run();
            } finally {
                ContextSnapshot.clear();
            }
        };
    }
}
