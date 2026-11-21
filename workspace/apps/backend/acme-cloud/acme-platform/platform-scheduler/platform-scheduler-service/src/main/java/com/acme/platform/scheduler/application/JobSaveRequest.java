package com.acme.platform.scheduler.application;

import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.TriggerType;

import java.time.Instant;

/**
 * 任务定义创建/更新入参（确认②④语义终稿）：
 * 创建强制默认 DISABLED（显式启用走 /status 端点）且必填字段在用例层显式校验
 * （jobKey/appName/handler/triggerType）；job_key 创建后不可改——更新携带的 jobKey 与既有值
 * 不一致即拒绝（SCHEDULER_JOB_KEY_IMMUTABLE，null=不变更放行）；更新为部分语义
 * （null 字段=保持既有值，携带字段=替换；乐观锁 version 必带由实体乐观锁承载）。
 *
 * @param jobKey            任务键（创建必填；更新可空=不变更，变更值≠既有值即拒绝）
 * @param appName           下发目标应用（Nacos 服务名）
 * @param handler           执行器 handler 标识
 * @param triggerType       触发类型（CRON/FIXED_RATE/ONCE）
 * @param cronExpr          CRON 表达式（CRON 必填；Spring CronExpression 6 位域）
 * @param fixedRateMs       固定频率毫秒（FIXED_RATE 必填正数）
 * @param onceFireAt        一次性触发时刻（ONCE 必填）
 * @param param             任务参数（jsonb 文本，可空）
 * @param retryPolicy       重试策略（可空=不重试/更新保持既有）
 * @param shardTotal        分片总数（可空=1）
 * @param timeoutMs         下发/执行超时毫秒（可空=60000）
 * @param misfirePolicy     misfire 策略（可空=FIRE_ONCE_NOW 缺省）
 * @param remark            备注（可空）
 * @param version           乐观锁版本（更新必带；创建忽略）
 */
public record JobSaveRequest(String jobKey, String appName, String handler, TriggerType triggerType,
                             String cronExpr, Long fixedRateMs, Instant onceFireAt, String param,
                             RetryPolicySpec retryPolicy, Integer shardTotal, Long timeoutMs,
                             MisfirePolicy misfirePolicy, String remark, Long version) {

    /** 重试策略落库文本（null=不重试）。 */
    public String retryPolicyJson(tools.jackson.databind.json.JsonMapper jsonMapper) {
        return retryPolicy() == null ? null : retryPolicy().toJson(jsonMapper);
    }
}
