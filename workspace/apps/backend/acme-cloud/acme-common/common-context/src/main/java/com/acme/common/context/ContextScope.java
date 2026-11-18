package com.acme.common.context;

/**
 * 上下文作用域（FR-012；BR-009）。作用域内异常退出必须仍完成清理（try-with-resources 保证）。
 * 统一语义（DP-006 本次定稿）：close 一律恢复进入前状态；进入前线程为空时等价于清理。
 * 嵌套作用域按栈式恢复，内层关闭不摧毁外层上下文。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class ContextScope implements AutoCloseable {

    private final ContextSnapshot entryState;

    private ContextScope(ContextSnapshot entryState) {
        this.entryState = entryState;
    }

    /**
     * 进入作用域：记录进入前状态，close 时恢复（FR-012；Modules.md 2.4.3）。
     * 工作线程/MQ 消费/定时任务在作用域内自行绑定上下文，close 后线程回到进入前状态。
     */
    public static ContextScope open() {
        return new ContextScope(ContextSnapshot.capture());
    }

    /**
     * 恢复快照至当前线程，close 时恢复进入前状态（FR-012）。
     */
    public static ContextScope open(ContextSnapshot snapshot) {
        ContextSnapshot entry = ContextSnapshot.capture();
        snapshot.restore();
        return new ContextScope(entry);
    }

    /** 恢复进入前状态（FR-012；Modules.md 2.4.3）。 */
    @Override
    public void close() {
        entryState.restore();
    }
}
