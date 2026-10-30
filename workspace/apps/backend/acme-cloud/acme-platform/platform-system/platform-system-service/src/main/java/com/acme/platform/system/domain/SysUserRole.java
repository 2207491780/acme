package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 用户-角色绑定（7.2.7：租户归属绑定表，纯 id 引用无对象关联；物理删除——软删行占用唯一键致重绑冲突，
 * 解绑 = DELETE；deleted 恒 false）。
 */
@Entity
@Table(name = "sys_user_role", uniqueConstraints = @UniqueConstraint(name = "uk_sys_user_role",
        columnNames = {"tenant_id", "user_id", "role_id"}))
public class SysUserRole extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    protected SysUserRole() {
    }

    public SysUserRole(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRoleId() {
        return roleId;
    }
}
