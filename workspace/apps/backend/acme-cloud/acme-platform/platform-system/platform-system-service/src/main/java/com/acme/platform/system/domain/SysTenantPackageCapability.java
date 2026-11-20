package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 套餐-能力绑定（REQ-20261121-054613 确认②：规范化绑定表，uk(package_id,capability_id)；
 * 能力为平台目录、绑定无租户归属；物理删除——绑定表 deleted 恒 false，重绑需物理解绑
 * 防唯一键冲突，sys_tenant_package_menu / sys_role_menu 同款）。
 */
@Table("sys_tenant_package_capability")
public class SysTenantPackageCapability extends AuditBase {

    @Column("package_id")
    private Long packageId;

    @Column("capability_id")
    private Long capabilityId;

    protected SysTenantPackageCapability() {
    }

    public SysTenantPackageCapability(Long packageId, Long capabilityId) {
        this.packageId = packageId;
        this.capabilityId = capabilityId;
    }

    public Long getPackageId() {
        return packageId;
    }

    public Long getCapabilityId() {
        return capabilityId;
    }
}
