package com.acme.framework.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * 租户过滤切片测试载体（15 章测试设计 2；仅测试域）。
 */
@Entity
@Table(name = "tenant_probe")
class TenantProbe {

    @Id
    private Long id;

    private String name;

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    protected TenantProbe() {
    }

    TenantProbe(Long id, String name, String tenantId) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getTenantId() {
        return tenantId;
    }
}
