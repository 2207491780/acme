package com.acme.platform.workflow.kernel;

/**
 * 命令责任链调用句柄（ContextInit→OptimisticLockRetry→Load→Validation→Execution→Persistence→
 * EventPublish 拦截器序列；链尾无下一位）。
 */
public interface CommandInvocation {

    /** 当前上下文。 */
    CommandContext context();

    /** 调用链下一位（链尾返回 null）。 */
    Object proceed();
}
