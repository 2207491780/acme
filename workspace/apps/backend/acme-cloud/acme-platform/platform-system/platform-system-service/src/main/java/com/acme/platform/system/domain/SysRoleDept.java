package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 角色-自定义部门绑定（REQ-20261114-120104：CUSTOM 数据范围的部门集来源；7.2.7 绑定表
 * 物理删除——scope 重写与删除清理走旁路，deleted 恒 false）。
 */
@Table("sys_role_dept")
public class SysRoleDept extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("role_id")
    private Long roleId;

    @Column("dept_id")
    private Long deptId;

    protected SysRoleDept() {
    }

    public SysRoleDept(Long roleId, Long deptId) {
        this.roleId = roleId;
        this.deptId = deptId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getDeptId() {
        return deptId;
    }
}
