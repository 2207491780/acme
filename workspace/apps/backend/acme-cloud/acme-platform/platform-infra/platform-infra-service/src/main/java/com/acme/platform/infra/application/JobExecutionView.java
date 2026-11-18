package com.acme.platform.infra.application;

import java.time.Instant;

/**
 * 任务执行记录视图（REQ-20261117-061017 §API GET /admin/v1/infra/jobs/{jobKey}/executions 行形态）：
 * id/jobKey/status/startedAt/finishedAt/error/triggerType/durationMs（wire Long→string id，
 * JobView 行同型）；durationMs = finishedAt − startedAt（执行中/缺终态为 null）。
 *
 * @param id          执行记录 id（雪花，字符串）
 * @param jobKey      任务标识
 * @param status      终态（RUNNING/SUCCESS/FAILED）
 * @param startedAt   开始时间
 * @param finishedAt  结束时间（执行中为 null）
 * @param error       失败摘要（成功为 null）
 * @param triggerType 触发来源（MANUAL/SCHEDULED）
 * @param durationMs  执行时长毫秒（缺终态为 null）
 */
public record JobExecutionView(String id, String jobKey, String status, Instant startedAt,
                               Instant finishedAt, String error, String triggerType, Long durationMs) {
}
