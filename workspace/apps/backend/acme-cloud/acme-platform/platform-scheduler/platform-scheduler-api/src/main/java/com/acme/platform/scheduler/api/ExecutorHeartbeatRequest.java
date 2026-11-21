package com.acme.platform.scheduler.api;

import java.util.List;

/**
 * 执行器心跳请求（设计规格 §5：10s 心跳，失败指数退避重试不影响业务）。
 * POST /internal/v1/scheduler/executors/heartbeat；未知实例按注册语义 upsert（失联重启自愈）。
 * JOB-2 执行器 SDK 对接契约，字段名不得变更。
 *
 * @param appName    执行器所属应用（Nacos 服务名）
 * @param instanceId 实例标识
 * @param handlers   最新 handler 清单（可空=沿用既有值，不清空）
 */
public record ExecutorHeartbeatRequest(String appName, String instanceId, List<String> handlers) {
}
