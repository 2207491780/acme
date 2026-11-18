package com.acme.framework.job.internal;

/**
 * 任务执行体（internal）：切面 {@code joinPoint.proceed()} 可能抛出 Throwable，签名显式声明（Modules.md 3.5）。
 */
@FunctionalInterface
public interface TaskBody {

    /** 执行业务体。 */
    Object call() throws Throwable;
}
