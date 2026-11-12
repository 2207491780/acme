package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户（7.2.1：平台级表，不标租户列、不参与过滤——租户目录自身；无软删，停用代替删除 FR-007）。
 */
@Table("sys_tenant")
public class SysTenant extends AuditBase {

    @Column("tenant_code")
    private String tenantCode;

    @Column("tenant_name")
    private String tenantName;

    @Column("status")
    private EnableStatus status;

    @Column("remark")
    private String remark;

    protected SysTenant() {
    }

    public SysTenant(String tenantCode, String tenantName, String remark) {
        this.tenantCode = tenantCode;
        this.tenantName = tenantName;
        this.status = EnableStatus.ENABLED;
        this.remark = remark;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
