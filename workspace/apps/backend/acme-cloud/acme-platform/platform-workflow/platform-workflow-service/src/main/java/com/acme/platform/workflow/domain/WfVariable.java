package com.acme.platform.workflow.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.workflow.infrastructure.mapper.JsonbTypeHandler;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 流程变量（wf_variable；契约确认②终稿）。uk(instance_id,name)；value jsonb（字符串/数值/布尔/
 * 对象均 JSON 序列化，读回按 JSON 结构还原——数值→Long/Double、布尔→Boolean、对象→Map）。
 * 子流程变量遮蔽（读穿透写不穿透）以 scope 限定名承载（VariableScope 组合键，确认② instance 级
 * 单表约束下的遮蔽实现位）。
 */
@Table("wf_variable")
public class WfVariable extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("instance_id")
    private Long instanceId;

    @Column("name")
    private String name;

    @Column(value = "value", typeHandler = JsonbTypeHandler.class)
    private String valueJson;

    public String getTenantId() {
        return tenantId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getName() {
        return name;
    }

    public String getValueJson() {
        return valueJson;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;
    }
}
