package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 岗位（REQ-20261114-120104：租户归属实体，(tenant_id, position_code) 租户内唯一；软删除）。
 */
@Table("sys_position")
public class SysPosition extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("position_code")
    private String positionCode;

    @Column("position_name")
    private String positionName;

    @Column("status")
    private EnableStatus status;

    protected SysPosition() {
    }

    public SysPosition(String code, String name, EnableStatus status) {
        this.positionCode = code;
        this.positionName = name;
        this.status = status == null ? EnableStatus.ENABLED : status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPositionCode() {
        return positionCode;
    }

    public void setPositionCode(String positionCode) {
        this.positionCode = positionCode;
    }

    public String getPositionName() {
        return positionName;
    }

    public void setPositionName(String positionName) {
        this.positionName = positionName;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
