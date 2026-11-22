package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户套餐（REQ-20261121-022910 确认③：平台级目录表，无 tenant_id、不参与租户过滤——
 * sys_menu/sys_tenant 同款 Q-D04 口径；软删除；uk(package_name) 由 DDL 承载，逻辑校验在用例层）。
 * REQ-20261122-150802 增补 quota_storage_bytes（可空=NULL 继承平台缺省；配额三级覆盖链套餐级）。
 */
@Table("sys_tenant_package")
public class SysTenantPackage extends AuditBase {

    @Column("package_name")
    private String packageName;

    @Column("remark")
    private String remark;

    /** 套餐级存储配额字节（可空=NULL 继承平台缺省；REQ-20261122-150802 确认③）。 */
    @Column("quota_storage_bytes")
    private Long quotaStorageBytes;

    protected SysTenantPackage() {
    }

    public SysTenantPackage(String packageName, String remark) {
        this.packageName = packageName;
        this.remark = remark;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getQuotaStorageBytes() {
        return quotaStorageBytes;
    }

    public void setQuotaStorageBytes(Long quotaStorageBytes) {
        this.quotaStorageBytes = quotaStorageBytes;
    }
}
