package com.acme.framework.tenant.app;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户过滤探针实体（切片测试专用，16.1）：覆盖租户列自动过滤、insert 值注入与未绑定写入拒绝。
 */
@Table("tenant_probe")
public class TenantProbe extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    private String name;

    public TenantProbe() {
    }

    public TenantProbe(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
