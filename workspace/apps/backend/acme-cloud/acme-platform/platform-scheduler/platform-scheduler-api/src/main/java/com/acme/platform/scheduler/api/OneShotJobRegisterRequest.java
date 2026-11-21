package com.acme.platform.scheduler.api;

import java.time.Instant;

/**
 * 一次性任务注册请求（设计规格 §7 BPM 联动协议；契约确认④：POST /internal/v1/scheduler/jobs，
 * trigger=ONCE@onceFireAt，jobKey 唯一，注册即返回，供 BPM-6 定时事件开通位）。
 * 注册即 ENABLED 并由调度内核在 onceFireAt 触发下发（AC-12 实证）。
 * job_key 撞既有键 → SCHEDULER_JOB_KEY_DUPLICATE（BPM 侧以含业务键的 jobKey 保证唯一）。
 *
 * @param jobKey            任务键（全局唯一）
 * @param appName           下发目标应用（Nacos 服务名，如 workflow 服务）
 * @param handler           下发 handler 标识（回调端点按此路由）
 * @param onceFireAt        触发时刻（过去时刻按 misfire 策略处置，默认 FIRE_ONCE_NOW 补跑）
 * @param param             任务参数（jsonb 文本透传，可空）
 * @param timeoutMs         下发/执行超时毫秒（可空=内核缺省）
 * @param retryMaxAttempts  重试上限（可空=内核缺省 0 不重试）
 * @param retryInitialIntervalMs 重试首间隔毫秒（可空=内核缺省）
 */
public record OneShotJobRegisterRequest(String jobKey, String appName, String handler,
                                        Instant onceFireAt, String param, Long timeoutMs,
                                        Integer retryMaxAttempts, Long retryInitialIntervalMs) {
}
