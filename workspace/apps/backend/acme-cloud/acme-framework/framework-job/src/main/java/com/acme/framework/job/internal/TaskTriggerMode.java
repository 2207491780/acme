package com.acme.framework.job.internal;

/**
 * 触发模式标记（internal）：{@code TaskTrigger} 手动触发置位，切面读取后以 triggerMode 语义互斥
 * （未获锁抛 TASK_RUNNING）；结束后清理（Modules.md 3.5 internal，模块外不得引用）。
 */
public final class TaskTriggerMode {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private TaskTriggerMode() {
    }

    /** 进入触发模式。 */
    public static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    /** 是否触发模式。 */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** 退出并清理。 */
    public static void exit() {
        ACTIVE.remove();
    }
}
