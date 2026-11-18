package com.acme.framework.job.support;

import java.time.Instant;

/**
 * 任务执行结果（FR-027/028）：执行日志载体（结构化日志同源字段，Q-005 不落库）。
 */
public record TaskResult(String taskId, boolean success, Instant startedAt, Instant finishedAt,
                         String failureReason) {

    public static TaskResult success(String taskId, Instant startedAt, Instant finishedAt) {
        return new TaskResult(taskId, true, startedAt, finishedAt, null);
    }

    public static TaskResult failure(String taskId, Instant startedAt, Instant finishedAt, String failureReason) {
        return new TaskResult(taskId, false, startedAt, finishedAt, failureReason);
    }
}
