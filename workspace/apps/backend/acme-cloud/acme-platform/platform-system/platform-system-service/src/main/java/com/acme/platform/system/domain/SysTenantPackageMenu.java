package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 套餐-菜单绑定（REQ-20261121-022910 确认③：规范化绑定表，uk(package_id,menu_id)；菜单为平台目录、
 * 绑定无租户归属；物理删除——绑定表 deleted 恒 false，重绑需物理解绑防唯一键冲突，sys_role_menu 同款）。
 */
@Table("sys_tenant_package_menu")
public class SysTenantPackageMenu extends AuditBase {

    @Column("package_id")
    private Long packageId;

    @Column("menu_id")
    private Long menuId;

    protected SysTenantPackageMenu() {
    }

    public SysTenantPackageMenu(Long packageId, Long menuId) {
        this.packageId = packageId;
        this.menuId = menuId;
    }

    public Long getPackageId() {
        return packageId;
    }

    public Long getMenuId() {
        return menuId;
    }
}
