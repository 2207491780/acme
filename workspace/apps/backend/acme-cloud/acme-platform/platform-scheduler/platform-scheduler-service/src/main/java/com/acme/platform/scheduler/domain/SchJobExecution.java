package com.acme.platform.scheduler.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.api.ExecutionTriggerType;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 执行历史（契约确认③：sch_job_execution；结果记录不恢复不删除——软删列对齐 AuditBase 统一口径恒 false）。
 * 幂等：uk(def_id, fire_time) WHERE trigger_type='SCHEDULE' AND attempt=0 由 DDL 部分唯一索引承载，
 * 并发双写由仓储层捕获唯一冲突转 skip（确认⑤：同 fire_time 至多一条 SCHEDULE execution）。
 */
@Table("sch_job_execution")
public class SchJobExecution extends AuditBase {

    /** 任务定义 id。 */
    @Column("definition_id")
    private Long definitionId;

    /** 任务键（冗余列：定义删除后历史仍可读）。 */
    @Column("job_key")
    private String jobKey;

    /** 触发来源（SCHEDULE / MANUAL / RETRY / EVENT）。 */
    @Column("trigger_type")
    private ExecutionTriggerType triggerType;

    /** 计划触发时刻（SCHEDULE 唯一索引组成列）。 */
    @Column("fire_time")
    private Instant fireTime;

    /** 状态机（PENDING → DISPATCHED → RUNNING → 终态）。 */
    @Column("state")
    private ExecutionState state;

    /** 重试轮次（0=首发；RETRY 链逐次 +1）。 */
    @Column("attempt")
    private Integer attempt;

    /** 分片序号（可空=未分片；真分片扇出归 JOB-4）。 */
    @Column("shard_index")
    private Integer shardIndex;

    /** 结果摘要（jsonb 文本，可空；终态上报承载）。 */
    @Column(value = "result", typeHandler = com.acme.platform.scheduler.infrastructure.mapper.JsonbTypeHandler.class)
    private String result;

    /** 失败信息（下发失败口径 NO_EXECUTOR / DISPATCH_TIMEOUT / DISPATCH_REJECTED / DISPATCH_FAILED 或执行错误，可空）。 */
    @Column("error")
    private String error;

    /** 执行耗时毫秒（可空）。 */
    @Column("cost_ms")
    private Long costMs;

    /** 链路追踪 id（下发 body 透传同值）。 */
    @Column("trace_id")
    private String traceId;

    public SchJobExecution() {
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(Long definitionId) {
        this.definitionId = definitionId;
    }

    public String getJobKey() {
        return jobKey;
    }

    public void setJobKey(String jobKey) {
        this.jobKey = jobKey;
    }

    public ExecutionTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(ExecutionTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public Instant getFireTime() {
        return fireTime;
    }

    public void setFireTime(Instant fireTime) {
        this.fireTime = fireTime;
    }

    public ExecutionState getState() {
        return state;
    }

    public void setState(ExecutionState state) {
        this.state = state;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Integer getShardIndex() {
        return shardIndex;
    }

    public void setShardIndex(Integer shardIndex) {
        this.shardIndex = shardIndex;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
