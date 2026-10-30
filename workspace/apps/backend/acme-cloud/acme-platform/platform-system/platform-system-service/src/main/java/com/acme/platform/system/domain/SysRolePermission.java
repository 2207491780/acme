package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 角色-权限点绑定（7.2.7：接口级授权数据，权限点为平台目录、绑定含租户归属；物理删除）。
 */
@Entity
@Table(name = "sys_role_permission", uniqueConstraints = @UniqueConstraint(name = "uk_sys_role_permission",
        columnNames = {"tenant_id", "role_id", "permission_id"}))
public class SysRolePermission extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_id", nullable = false)
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
