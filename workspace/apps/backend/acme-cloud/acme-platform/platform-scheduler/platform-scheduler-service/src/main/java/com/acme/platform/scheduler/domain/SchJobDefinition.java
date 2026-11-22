package com.acme.platform.scheduler.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.api.TriggerType;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 任务定义（契约确认③：sch_job_definition；软删除；uk(job_key) 由 DDL 承载）。
 * tenant_id 可空 = 平台任务（设计规格 §2：后续租户级任务配额预留 TENANT-4；本 REQ 无租户过滤语义，
 * 列仅预留，不标注 tenantId=true——调度域为平台级控制面，触发与租户上下文解耦）。
 * param / retry_policy 走 jsonb 文本形态（JsonbTypeHandler，platform-system sys_user_preference 同款）。
 */
@Table("sch_job_definition")
public class SchJobDefinition extends AuditBase {

    /** 任务键（全局唯一，创建后不可改——SCHEDULER_JOB_KEY_IMMUTABLE）。 */
    @Column("job_key")
    private String jobKey;

    /** 下发目标应用（Nacos 服务名，lb://{app_name} 寻址）。 */
    @Column("app_name")
    private String appName;

    /** 执行器侧 handler 标识（@JobHandler 注册名）。 */
    @Column("handler")
    private String handler;

    /** 触发类型（CRON / FIXED_RATE / ONCE）。 */
    @Column("trigger_type")
    private TriggerType triggerType;

    /** CRON 表达式（trigger_type=CRON 必填；Spring CronExpression 6 位域）。 */
    @Column("cron_expr")
    private String cronExpr;

    /** 固定频率毫秒（trigger_type=FIXED_RATE 必填，正数）。 */
    @Column("fixed_rate_ms")
    private Long fixedRateMs;

    /** 一次性触发时刻（trigger_type=ONCE 必填）。 */
    @Column("once_fire_at")
    private Instant onceFireAt;

    /** 任务参数（jsonb 文本原样透传下发 body，可空）。 */
    @Column(value = "param", typeHandler = com.acme.platform.scheduler.infrastructure.mapper.JsonbTypeHandler.class)
    private String param;

    /** 重试策略（jsonb 文本 {"maxAttempts":n,"initialIntervalMs":ms}，可空=不重试）。 */
    @Column(value = "retry_policy", typeHandler = com.acme.platform.scheduler.infrastructure.mapper.JsonbTypeHandler.class)
    private String retryPolicy;

    /** 分片总数（默认 1；真分片扇出归 JOB-4）。 */
    @Column("shard_total")
    private Integer shardTotal;

    /** 分片扇出开关（REQ-20261122-150756 载体裁决⑥：调度侧列=扇出权威开关；
     *  扇出条件=shardable && shardTotal>1；false/缺省=单发兼容，SDK 注解值仅 advisory）。 */
    @Column("shardable")
    private Boolean shardable;

    /** 下发/执行超时毫秒（默认 60000；超时巡检口径）。 */
    @Column("timeout_ms")
    private Long timeoutMs;

    /** misfire 策略（FIRE_ONCE_NOW / IGNORE）。 */
    @Column("misfire_policy")
    private MisfirePolicy misfirePolicy;

    /** 启停状态（创建默认 DISABLED，显式启用）。 */
    @Column("status")
    private SchedulerJobStatus status;

    /** 下次触发时刻（可空=ONCE 已触发 / 未启用；触发后即时计算落库，确认⑤）。 */
    @Column("next_fire_time")
    private Instant nextFireTime;

    /** 租户 id（可空=平台任务；TENANT-4 预留列，本 REQ 不参与过滤）。 */
    @Column("tenant_id")
    private String tenantId;

    /** 备注（可空）。 */
    @Column("remark")
    private String remark;

    public SchJobDefinition() {
    }

    public String getJobKey() {
        return jobKey;
    }

    public void setJobKey(String jobKey) {
        this.jobKey = jobKey;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(TriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getCronExpr() {
        return cronExpr;
    }

    public void setCronExpr(String cronExpr) {
        this.cronExpr = cronExpr;
    }

    public Long getFixedRateMs() {
        return fixedRateMs;
    }

    public void setFixedRateMs(Long fixedRateMs) {
        this.fixedRateMs = fixedRateMs;
    }

    public Instant getOnceFireAt() {
        return onceFireAt;
    }

    public void setOnceFireAt(Instant onceFireAt) {
        this.onceFireAt = onceFireAt;
    }

    public String getParam() {
        return param;
    }

    public void setParam(String param) {
        this.param = param;
    }

    public String getRetryPolicy() {
        return retryPolicy;
    }

    public void setRetryPolicy(String retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public Integer getShardTotal() {
        return shardTotal;
    }

    public void setShardTotal(Integer shardTotal) {
        this.shardTotal = shardTotal;
    }

    public Boolean getShardable() {
        return shardable;
    }

    public void setShardable(Boolean shardable) {
        this.shardable = shardable;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public MisfirePolicy getMisfirePolicy() {
        return misfirePolicy;
    }

    public void setMisfirePolicy(MisfirePolicy misfirePolicy) {
        this.misfirePolicy = misfirePolicy;
    }

    public SchedulerJobStatus getStatus() {
        return status;
    }

    public void setStatus(SchedulerJobStatus status) {
        this.status = status;
    }

    public Instant getNextFireTime() {
        return nextFireTime;
    }

    public void setNextFireTime(Instant nextFireTime) {
        this.nextFireTime = nextFireTime;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
