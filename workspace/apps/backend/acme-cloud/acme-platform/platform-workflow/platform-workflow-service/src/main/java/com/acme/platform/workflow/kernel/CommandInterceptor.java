package com.acme.platform.workflow.kernel;

/**
 * 命令责任链拦截器（设计规格 §3.1 责任链扩展点）。
 */
public interface CommandInterceptor {

    /** 拦截处理（返回链结果；异常上抛驱动事务回滚）。 */
    Object intercept(CommandInvocation invocation);
}
