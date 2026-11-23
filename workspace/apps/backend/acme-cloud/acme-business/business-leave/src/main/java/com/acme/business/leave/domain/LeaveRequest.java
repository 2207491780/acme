package com.acme.business.leave.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 请假单（leave_request；REQ-20261122-233425 确认⑤字段域终稿）。instance_id 绑定流程实例
 * （WorkflowRuntimeClient 启动成功回写）；status 语义域见 {@link LeaveStatus}。
 */
@Table("leave_request")
public class LeaveRequest extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("user_id")
    private String userId;

    @Column("user_name")
    private String userName;

    @Column("leave_type")
    private String leaveType;

    @Column("reason")
    private String reason;

    @Column("start_time")
    private Instant startTime;

    @Column("end_time")
    private Instant endTime;

    @Column("status")
    private LeaveStatus status;

    @Column("instance_id")
    private Long instanceId;

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public String getLeaveType() {
        return leaveType;
    }

    public String getReason() {
        return reason;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public LeaveStatus getStatus() {
        return status;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public void setStatus(LeaveStatus status) {
        this.status = status;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }
}
