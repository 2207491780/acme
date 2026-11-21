package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.infrastructure.mapper.JsonbTypeHandler;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;
import java.util.Map;

/**
 * 任务（wf_task；契约确认②终稿）。status CREATED|CLAIMED|COMPLETED|REJECTED|CANCELLED；
 * form_data jsonb（complete 提交）；version 乐观锁（并发 claim/complete CAS，AC-7）。
 */
@Table("wf_task")
public class WfTask extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("instance_id")
    private Long instanceId;

    @Column("execution_id")
    private Long executionId;

    @Column("node_id")
    private String nodeId;

    @Column("node_name")
    private String nodeName;

    @Column("assignee")
    private String assignee;

    @Column("status")
    private WorkflowTaskStatus status;

    @Column(value = "form_data", typeHandler = JsonbTypeHandler.class)
    private String formDataJson;

    @Column("due_date")
    private Instant dueDate;

    @Column("claim_time")
    private Instant claimTime;

    @Column("complete_time")
    private Instant completeTime;

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

    public String getNodeName() {
        return nodeName;
    }

    public String getAssignee() {
        return assignee;
    }

    public WorkflowTaskStatus getStatus() {
        return status;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public Instant getClaimTime() {
        return claimTime;
    }

    public Instant getCompleteTime() {
        return completeTime;
    }

    public String getFormDataJson() {
        return formDataJson;
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

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public void setStatus(WorkflowTaskStatus status) {
        this.status = status;
    }

    public void setDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public void setClaimTime(Instant claimTime) {
        this.claimTime = claimTime;
    }

    public void setCompleteTime(Instant completeTime) {
        this.completeTime = completeTime;
    }

    public void setFormDataJson(String formDataJson) {
        this.formDataJson = formDataJson;
    }
}
