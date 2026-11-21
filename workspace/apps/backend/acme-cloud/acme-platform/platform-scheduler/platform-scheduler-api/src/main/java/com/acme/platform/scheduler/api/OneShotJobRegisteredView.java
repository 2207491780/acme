package com.acme.platform.scheduler.api;

import java.time.Instant;

/**
 * 一次性任务注册响应视图（SchedulerJobClient 契约回参；服务端 InternalJobRegistrationController
 * 返回完整 JobView，本视图为其最小投影——多余 JSON 字段由消费方 Jackson 宽松解码忽略）。
 *
 * @param id           任务定义 id
 * @param jobKey       任务键（wf-timer:{timerJobId}）
 * @param status       任务状态（ENABLED）
 * @param nextFireTime 首次触发时刻（= onceFireAt）
 */
public record OneShotJobRegisteredView(Long id, String jobKey, String status, Instant nextFireTime) {
}
