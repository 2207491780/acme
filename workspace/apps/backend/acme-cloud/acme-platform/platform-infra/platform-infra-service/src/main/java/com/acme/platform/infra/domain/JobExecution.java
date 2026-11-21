package com.acme.platform.infra.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 任务执行记录（REQ-20261114-071607 §变更清单 sys_job_execution：job_key/status/started_at/
 * finished_at/error/trigger + 审计基类口径）：手动触发与调度两径统一落库（FR-028 两径一致）；
 * 行在锁内创建（ShedLock 互斥成功后），status 流转 RUNNING → SUCCESS/FAILED。
 * trigger 列名取 trigger_type（trigger 为 PG 保留字不可作列名，语义不变）。
 */
@Table("sys_job_execution")
public class JobExecution extends AuditBase {

    /** 状态：执行中。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：执行成功。 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 状态：执行失败。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 触发来源：手动触发（REQ-20261121-200724 确认⑦ triggerType 归一取值）。 */
    public static final String TRIGGER_MANUAL = "TRIGGER_MANUAL";

    /** 触发来源：本地调度兜底（REQ-20261121-200724 确认⑦ triggerType 归一取值）。 */
    public static final String TRIGGER_SCHEDULED = "TRIGGER_SCHEDULED";

    /** 触发来源：调度中心下发执行（REQ-20261121-200724 确认⑦ triggerType 归一取值；SDK 下发路径恒传）。 */
    public static final String TRIGGER_DISPATCHED = "SCHEDULER_DISPATCH";

    @Column("job_key")
    private String jobKey;

    @Column("status")
    private String status;

    @Column("started_at")
    private Instant startedAt;

    @Column("finished_at")
    private Instant finishedAt;

    @Column("error")
    private String error;

    @Column("trigger_type")
    private String triggerType;

    protected JobExecution() {
    }

    public JobExecution(String jobKey, String status, Instant startedAt, String triggerType) {
        this.jobKey = jobKey;
        this.status = status;
        this.startedAt = startedAt;
        this.triggerType = triggerType;
    }

    public String getJobKey() {
        return jobKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getTriggerType() {
        return triggerType;
    }
}
