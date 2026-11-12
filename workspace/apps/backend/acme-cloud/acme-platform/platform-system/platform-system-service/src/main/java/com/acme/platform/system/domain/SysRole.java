package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 角色（7.2.3：租户归属，(tenant_id, role_code) 唯一；软删除）。
 * 支持父子关系树状定义：根哨兵 0，树形经 parentId + TreeBuilder 组装，无对象关联（sys_menu 同款）。
 */
@Table("sys_role")
public class SysRole extends AuditBase {

    /** 根角色 parentId 哨兵（无对象关联，树组装时映射为 null）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("parent_id")
    private Long parentId;

    @Column("role_code")
    private String roleCode;

    @Column("role_name")
    private String roleName;

    @Column("status")
    private EnableStatus status;

    @Column("remark")
    private String remark;

    @Column("built_in")
    private Boolean builtIn;

    protected SysRole() {
    }

    public SysRole(String roleCode, String roleName, String remark, Boolean builtIn) {
        this(SysRole.ROOT_PARENT_ID, roleCode, roleName, remark, builtIn);
    }

    public SysRole(Long parentId, String roleCode, String roleName, String remark, Boolean builtIn) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.status = EnableStatus.ENABLED;
        this.remark = remark;
        this.builtIn = builtIn != null && builtIn;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Boolean getBuiltIn() {
        return builtIn;
    }
}
