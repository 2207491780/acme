package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 部门（REQ-20261114-120104：租户归属实体，租户列自动注入与过滤 12.4.1；软删除）。
 * 树状定义：根哨兵 0（sys_role/sys_menu 同款），path 物化路径 "/父链/id/" 尾斜杠形态——
 * 子树解析按前缀 LIKE（尾斜杠杜绝 "/10/" 误匹配 "/100/"）；创建 / 重挂父时由用例层回填，
 * 无对象关联（纯 id 引用）。
 */
@Table("sys_dept")
public class SysDept extends AuditBase {

    /** 根部门 parentId 哨兵（无对象关联，树组装时映射为 null）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("parent_id")
    private Long parentId;

    @Column("path")
    private String path;

    @Column("dept_name")
    private String deptName;

    @Column("sort_order")
    private Integer sortOrder;

    @Column("status")
    private EnableStatus status;

    protected SysDept() {
    }

    /** 物化 path 定向更新模板（updatePath 装配回填用；仅携带 path 列，同 SysMessage.readTemplate 先例）。 */
    public static SysDept pathTemplate(String path) {
        SysDept dept = new SysDept();
        dept.path = path;
        return dept;
    }

    public SysDept(Long parentId, String name, Integer sortOrder, EnableStatus status) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        this.deptName = name;
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
        this.status = status == null ? EnableStatus.ENABLED : status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
