package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 角色-菜单绑定（7.2.7：菜单级授权数据，菜单为平台目录、绑定含租户归属；物理删除——
 * 绑定表 deleted 恒 false，解绑 = 物理删除）。
 */
@Table("sys_role_menu")
public class SysRoleMenu extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("role_id")
    private Long roleId;

    @Column("menu_id")
    private Long menuId;

    protected SysRoleMenu() {
    }

    public SysRoleMenu(Long roleId, Long menuId) {
        this.roleId = roleId;
        this.menuId = menuId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getMenuId() {
        return menuId;
    }
}
