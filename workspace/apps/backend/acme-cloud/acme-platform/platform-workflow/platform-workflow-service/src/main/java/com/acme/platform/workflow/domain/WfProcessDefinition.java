package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 流程定义（wf_process_definition；契约确认②终稿）。平台级共享（无 tenant_id——设计规格 §1
 * 「流程定义平台级共享、实例租户级隔离」+ 契约确认②「definition 平台级无 tenant_id」）。
 * key+version 唯一（同 key 部署自增 version）；bpmn_xml 部署态即编译事实源（运行时零解析）；
 * layout_json 为设计器布局预留列（BPM-2 消费，引擎不读）。
 */
@Table("wf_process_definition")
public class WfProcessDefinition extends AuditBase {

    @Column("process_key")
    private String processKey;

    /**
     * 业务版本（同 key 部署自增；uk(process_key, deploy_version)）。列名 deploy_version——
     * AuditBase 审计口径的乐观锁列固定占名 version（sys_* 同款），业务版本另列承载。
     */
    @Column("deploy_version")
    private Integer deployVersion;

    @Column("name")
    private String name;

    @Column("bpmn_xml")
    private String bpmnXml;

    @Column("status")
    private WorkflowDefinitionStatus status;

    @Column(value = "layout_json", typeHandler = com.acme.framework.flex.typehandler.JsonbTypeHandler.class)
    private String layoutJson;

    @Column("deployed_at")
    private java.time.Instant deployedAt;

    protected WfProcessDefinition() {
    }

    public static WfProcessDefinition create(String processKey, Integer deployVersion, String name,
                                             WorkflowDefinitionStatus status) {
        WfProcessDefinition definition = new WfProcessDefinition();
        definition.processKey = processKey;
        definition.deployVersion = deployVersion;
        definition.name = name;
        definition.status = status;
        return definition;
    }

    public String getProcessKey() {
        return processKey;
    }

    public Integer getDeployVersion() {
        return deployVersion;
    }

    public String getName() {
        return name;
    }

    public String getBpmnXml() {
        return bpmnXml;
    }

    public WorkflowDefinitionStatus getStatus() {
        return status;
    }

    public String getLayoutJson() {
        return layoutJson;
    }

    public java.time.Instant getDeployedAt() {
        return deployedAt;
    }

    public void setProcessKey(String processKey) {
        this.processKey = processKey;
    }

    public void setDeployVersion(Integer deployVersion) {
        this.deployVersion = deployVersion;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setBpmnXml(String bpmnXml) {
        this.bpmnXml = bpmnXml;
    }

    public void setStatus(WorkflowDefinitionStatus status) {
        this.status = status;
    }

    public void setLayoutJson(String layoutJson) {
        this.layoutJson = layoutJson;
    }

    public void setDeployedAt(java.time.Instant deployedAt) {
        this.deployedAt = deployedAt;
    }
}
