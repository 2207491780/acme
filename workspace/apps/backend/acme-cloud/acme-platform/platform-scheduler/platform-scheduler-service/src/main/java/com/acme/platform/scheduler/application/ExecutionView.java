package com.acme.platform.scheduler.application;

import java.time.Instant;

/**
 * 执行历史视图（管理面 /admin/v1/scheduler/executions；状态/耗时/结果/错误/traceId，设计规格 §6）。
 *
 * @param id           执行记录 id（wire 字符串化）
 * @param definitionId 任务定义 id（wire 字符串化）
 * @param jobKey       任务键
 * @param triggerType  触发来源（SCHEDULE/MANUAL/RETRY/EVENT）
 * @param fireTime     计划触发时刻
 * @param state        状态机
 * @param attempt      重试轮次
 * @param shardIndex   分片序号（可空）
 * @param result       结果摘要（jsonb 文本，可空）
 * @param error        失败信息（可空）
 * @param costMs       执行耗时毫秒（可空）
 * @param traceId      链路追踪 id
 */
public record ExecutionView(String id, String definitionId, String jobKey, String triggerType,
                            Instant fireTime, String state, Integer attempt, Integer shardIndex,
                            String result, String error, Long costMs, String traceId) {
}
