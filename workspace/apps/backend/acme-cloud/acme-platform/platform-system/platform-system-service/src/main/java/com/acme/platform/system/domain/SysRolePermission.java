package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 角色-权限点绑定（7.2.7：接口级授权数据，权限点为平台目录、绑定含租户归属；物理删除——
 * 绑定表 deleted 恒 false，解绑 = 物理删除）。
 */
@Table("sys_role_permission")
public class SysRolePermission extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("role_id")
    private Long roleId;

    @Column("permission_id")
    private Long permissionId;

    protected SysRolePermission() {
    }

    public SysRolePermission(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getPermissionId() {
        return permissionId;
    }
}
