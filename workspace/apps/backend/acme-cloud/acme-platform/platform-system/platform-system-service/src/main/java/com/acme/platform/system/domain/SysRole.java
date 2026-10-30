package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 角色（7.2.3：租户归属，(tenant_id, role_code) 唯一；@SoftDelete 软删除）。
 * 支持父子关系树状定义：根哨兵 0，树形经 parentId + TreeBuilder 组装，无 JPA 对象关联（sys_menu 同款）。
 */
@Entity
@SoftDelete
@Table(name = "sys_role", uniqueConstraints = @UniqueConstraint(name = "uk_sys_role_tenant_code",
        columnNames = {"tenant_id", "role_code"}),
        indexes = @Index(name = "idx_sys_role_parent", columnList = "parent_id"))
public class SysRole extends AuditBase {

    /** 根角色 parentId 哨兵（无 JPA 关联，树组装时映射为 null）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "role_code", length = 64, nullable = false)
    private String roleCode;

    @Column(name = "role_name", length = 64, nullable = false)
    private String roleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "built_in", nullable = false)
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
