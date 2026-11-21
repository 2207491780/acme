package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.workflow.infrastructure.mapper.JsonbTypeHandler;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 历史活动（wf_history_activity；契约确认②终稿）。action ENTER|LEAVE|REJECT_EDGE|TIMER_FIRE
 * （AC-15 按序完整）；detail jsonb 承载驳回边/定时器/委托记录等扩展明细。查询面（历史链）只读。
 */
@Table("wf_history_activity")
public class WfHistoryActivity extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("instance_id")
    private Long instanceId;

    @Column("node_id")
    private String nodeId;

    @Column("node_type")
    private String nodeType;

    @Column("action")
    private String action;

    @Column("assignee")
    private String assignee;

    @Column("start_at")
    private Instant startAt;

    @Column("end_at")
    private Instant endAt;

    @Column(value = "detail", typeHandler = JsonbTypeHandler.class)
    private String detailJson;

    public String getTenantId() {
        return tenantId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getAction() {
        return action;
    }

    public String getAssignee() {
        return assignee;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }
}
