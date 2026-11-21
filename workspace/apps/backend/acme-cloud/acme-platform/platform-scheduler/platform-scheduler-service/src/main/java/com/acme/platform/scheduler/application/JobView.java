package com.acme.platform.scheduler.application;

import java.time.Instant;

/**
 * 任务定义视图（管理面 /admin/v1/scheduler/jobs；设计规格 §6 管理页 JOB-3 数据面）。
 *
 * @param id                定义 id（wire 字符串化，全局 Long→String）
 * @param jobKey            任务键（全局唯一，创建后不可改）
 * @param appName           下发目标应用（Nacos 服务名）
 * @param handler           执行器 handler 标识
 * @param triggerType       触发类型（CRON/FIXED_RATE/ONCE）
 * @param cronExpr          CRON 表达式（CRON 类型时非空）
 * @param fixedRateMs       固定频率毫秒（FIXED_RATE 类型时非空）
 * @param onceFireAt        一次性触发时刻（ONCE 类型时非空）
 * @param param             任务参数（jsonb 文本原样）
 * @param retryPolicy       重试策略（jsonb 文本原样）
 * @param shardTotal        分片总数
 * @param timeoutMs         下发/执行超时毫秒
 * @param misfirePolicy     misfire 策略
 * @param status            启停状态（创建默认 DISABLED）
 * @param nextFireTime      下次触发时刻（可空）
 * @param remark            备注
 * @param version           乐观锁版本（wire 字符串化；更新必带）
 */
public record JobView(String id, String jobKey, String appName, String handler, String triggerType,
                      String cronExpr, Long fixedRateMs, Instant onceFireAt, String param,
                      String retryPolicy, Integer shardTotal, Long timeoutMs, String misfirePolicy,
                      String status, Instant nextFireTime, String remark, Long version) {
}
