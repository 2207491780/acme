package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 定时任务（wf_timer_job；契约确认②终稿）。status PENDING|FIRED|CANCELLED；scheduler_job_key
 * uk（=wf-timer:{id}）+ 状态 CAS（PENDING→FIRED 单向迁移）承载 fire 回调幂等（确认⑥）。
 */
@Table("wf_timer_job")
public class WfTimerJob extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("instance_id")
    private Long instanceId;

    @Column("execution_id")
    private Long executionId;

    @Column("node_id")
    private String nodeId;

    @Column("boundary_event_id")
    private String boundaryEventId;

    @Column("due_at")
    private Instant dueAt;

    @Column("status")
    private String status;

    @Column("scheduler_job_key")
    private String schedulerJobKey;

    /** 定时任务状态常量（wf_timer_job.status 语义域）。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FIRED = "FIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public String getTenantId() {
        return tenantId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getBoundaryEventId() {
        return boundaryEventId;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getStatus() {
        return status;
    }

    public String getSchedulerJobKey() {
        return schedulerJobKey;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setBoundaryEventId(String boundaryEventId) {
        this.boundaryEventId = boundaryEventId;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSchedulerJobKey(String schedulerJobKey) {
        this.schedulerJobKey = schedulerJobKey;
    }
}
