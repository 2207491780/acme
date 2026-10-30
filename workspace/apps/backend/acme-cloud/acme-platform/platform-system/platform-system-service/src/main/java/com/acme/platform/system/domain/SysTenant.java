package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 租户（7.2.1：平台级表，不标 @TenantId、不参与过滤——租户目录自身；无软删，停用代替删除 FR-007）。
 */
@Entity
@Table(name = "sys_tenant")
public class SysTenant extends AuditBase {

    @Column(name = "tenant_code", length = 64, nullable = false, unique = true)
    private String tenantCode;

    @Column(name = "tenant_name", length = 128, nullable = false)
    private String tenantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    @Column(name = "remark", length = 255)
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
