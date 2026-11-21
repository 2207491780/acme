package com.acme.framework.job.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 任务声明（FR-026）：登记任务标识与互斥/锁参数；调度入口（@Scheduled 等）经切面互斥执行，
 * 手动触发经 {@code TaskTrigger} 同径（FR-028 两径一致，取舍 #10 编程式互斥）。
 *
 * @deprecated 仅保留本地语义（REQ-20261121-200724 确认③：可被调度中心调度的任务改标
 * {@link JobHandler}；两注解共存过渡，删除归后续清理 REQ）。存量使用点照常工作，不进执行器
 * handlers 清单、不接单。
 */
@Deprecated(since = "2026.11", forRemoval = false)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AcmeTask {

    /** 任务标识（集群内唯一，入锁 key 与执行日志）。 */
    String taskId();

    /** 互斥维度（默认 GLOBAL）。 */
    MutexMode mutex() default MutexMode.GLOBAL;

    /** 锁最长持有（ISO-8601，空=取 acme.job.lock-at-most-for）。 */
    String lockAtMostFor() default "";

    /** 锁最短持有（ISO-8601，空=取 acme.job.lock-at-least-for）。 */
    String lockAtLeastFor() default "";
}
