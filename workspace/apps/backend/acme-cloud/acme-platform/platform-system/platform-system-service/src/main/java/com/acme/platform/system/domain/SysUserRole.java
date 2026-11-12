package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 用户-角色绑定（7.2.7：租户归属绑定表，纯 id 引用无对象关联；物理删除——软删行占用唯一键致重绑冲突，
 * 解绑 = DELETE；deleted 恒 false）。
 */
@Table("sys_user_role")
public class SysUserRole extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("user_id")
    private Long userId;

    @Column("role_id")
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
