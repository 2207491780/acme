package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 角色-菜单绑定（7.2.7：菜单级授权数据，菜单为平台目录、绑定含租户归属；物理删除）。
 */
@Entity
@Table(name = "sys_role_menu", uniqueConstraints = @UniqueConstraint(name = "uk_sys_role_menu",
        columnNames = {"tenant_id", "role_id", "menu_id"}))
public class SysRoleMenu extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "menu_id", nullable = false)
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
