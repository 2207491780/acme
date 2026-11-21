package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 执行树（wf_execution；契约确认②终稿）——execution = 一个移动中的 token（设计规格 §3.2）。
 * parent_id 构成树形；并行 fork 产生 is_concurrent 子执行；子流程 entry 为 is_scope_root；
 * version 乐观锁。instance 维度租户隔离（@TenantId）。
 */
@Table("wf_execution")
public class WfExecution extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("instance_id")
    private Long instanceId;

    @Column("parent_id")
    private Long parentId;

    @Column("node_id")
    private String nodeId;

    @Column("node_type")
    private String nodeType;

    @Column("is_active")
    private Boolean active;

    @Column("is_concurrent")
    private Boolean concurrent;

    @Column("is_scope_root")
    private Boolean scopeRoot;

    public String getTenantId() {
        return tenantId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public Boolean getActive() {
        return active;
    }

    public Boolean getConcurrent() {
        return concurrent;
    }

    public Boolean getScopeRoot() {
        return scopeRoot;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public void setConcurrent(Boolean concurrent) {
        this.concurrent = concurrent;
    }

    public void setScopeRoot(Boolean scopeRoot) {
        this.scopeRoot = scopeRoot;
    }
}
