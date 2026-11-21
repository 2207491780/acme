package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 流程实例（wf_process_instance；契约确认②终稿）。version 乐观锁（并发推进 CAS，AC-7/AC-12）；
 * suspend_reason 承载 SUSPENDED 语义域（ADMIN_SUSPEND / SERVICE_TASK_FAILED / NO_ASSIGNEE_RESOLVED）。
 */
@Table("wf_process_instance")
public class WfProcessInstance extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("definition_id")
    private Long definitionId;

    @Column("business_key")
    private String businessKey;

    @Column("initiator")
    private String initiator;

    @Column("status")
    private WorkflowInstanceStatus status;

    @Column("suspend_reason")
    private String suspendReason;

    @Column("start_time")
    private Instant startTime;

    @Column("end_time")
    private Instant endTime;

    public String getTenantId() {
        return tenantId;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getInitiator() {
        return initiator;
    }

    public WorkflowInstanceStatus getStatus() {
        return status;
    }

    public String getSuspendReason() {
        return suspendReason;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setDefinitionId(Long definitionId) {
        this.definitionId = definitionId;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public void setInitiator(String initiator) {
        this.initiator = initiator;
    }

    public void setStatus(WorkflowInstanceStatus status) {
        this.status = status;
    }

    public void setSuspendReason(String suspendReason) {
        this.suspendReason = suspendReason;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }
}
