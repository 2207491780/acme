package com.acme.framework.core.config;

/**
 * 受管线程池拒绝策略（FR-001 异常分支授权阶段 2；架构设计 4.1/6）。
 */
public enum RejectionPolicy {

    /** 快速失败，提交方可感知（默认；BR-015）。 */
    ABORT,

    /** 由提交线程执行任务。 */
    CALLER_RUNS,

    /** 丢弃当前任务。 */
    DISCARD,

    /** 丢弃队列最旧任务后重试。 */
    DISCARD_OLDEST
}
