package com.acme.platform.scheduler.api;

import java.time.Instant;

/**
 * 下发指令（设计规格 §4 指令通道终稿；契约确认⑤ body 字段钉死——JOB-2 执行器 SDK 对接契约，
 * 字段名/路径不得变更）。scheduler 经 Nacos {@code lb://{app_name}} POST
 * {@code /internal/v1/job/executions/{executionId}/run}，body 即本记录（JSON，camelCase wire 名）。
 *
 * @param jobKey       任务键（sch_job_definition.job_key）
 * @param handler      执行器侧 handler 标识（{@code @JobHandler} 注册名，SDK 据此路由）
 * @param param        任务参数（sch_job_definition.param jsonb 文本原样透传，可空）
 * @param shardContext 分片上下文（默认单分片 {0,1}）
 * @param fireTime     计划触发时刻（本次执行的 fire_time）
 * @param traceId      链路追踪 id（framework-trace 生成；执行器侧日志/回传共用）
 * @param attempt      重试轮次（0=首发，RETRY 链逐次 +1）
 */
public record JobDispatchCommand(String jobKey, String handler, String param,
                                 ShardContext shardContext, Instant fireTime,
                                 String traceId, int attempt) {
}
