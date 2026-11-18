package com.acme.framework.job.support;

/**
 * 任务互斥维度（FR-029/AC-027、Q-006）：默认全局互斥，可声明按租户互斥。
 * {@link #TENANT} 无租户语境时退化全局锁并 {@code log.warn} 显式告警（FR-029 异常分支，不静默退化）。
 */
public enum MutexMode {

    /** 全局互斥（集群单实例执行，锁 key 不带租户段）。 */
    GLOBAL,

    /** 按租户互斥（有租户语境时 key 带租户段；无租户语境退化全局 + WARN 告警，FR-029 异常分支）。 */
    TENANT
}
