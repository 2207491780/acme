package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户配额覆盖（REQ-20261122-150802 确认③：平台级覆盖表，不参与租户过滤——tenant_id 为数据键
 * （逻辑引用 sys_tenant.id，无 FK 对齐库内惯例）非归属过滤列，sys_tenant 同款 Q-D04 口径；
 * uk(tenant_id) 由 DDL 承载，逻辑校验在用例层）。
 * quota_storage_bytes NULL=继承套餐（行保留仅置空，确认③清除语义）；0=不限。
 */
@Table("sys_tenant_quota")
public class SysTenantQuota extends AuditBase {

    /** 租户 ID（逻辑引用 sys_tenant.id；uk 由 DDL 承载）。 */
    @Column("tenant_id")
    private Long tenantId;

    /** 存储配额覆盖字节（可空=NULL 继承套餐；0=不限）。 */
    @Column("quota_storage_bytes")
    private Long quotaStorageBytes;

    protected SysTenantQuota() {
    }

    public SysTenantQuota(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getQuotaStorageBytes() {
        return quotaStorageBytes;
    }

    public void setQuotaStorageBytes(Long quotaStorageBytes) {
        this.quotaStorageBytes = quotaStorageBytes;
    }
}
