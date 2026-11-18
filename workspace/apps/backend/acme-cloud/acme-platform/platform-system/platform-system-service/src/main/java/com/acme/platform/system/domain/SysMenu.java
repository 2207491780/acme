package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 菜单（7.2.5：平台级目录表，无 tenant_id、不参与租户过滤——Q-D04；树形经 parentId + TreeBuilder 组装，
 * 无对象关联；软删除）。
 */
@Table("sys_menu")
public class SysMenu extends AuditBase {

    /** 根节点 parentId 哨兵（无对象关联，树组装时映射为 null）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @Column("parent_id")
    private Long parentId;

    @Column("menu_name")
    private String menuName;

    @Column("menu_type")
    private MenuType menuType;

    @Column("path")
    private String path;

    @Column("component")
    private String component;

    @Column("icon")
    private String icon;

    @Column("sort_order")
    private Integer sortOrder;

    @Column("visible")
    private Boolean visible;

    @Column("permission_code")
    private String permissionCode;

    @Column("status")
    private EnableStatus status;

    protected SysMenu() {
    }

    public SysMenu(Long parentId, String menuName, MenuType menuType, String path, String component,
                   String icon, Integer sortOrder, Boolean visible, String permissionCode) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        this.menuName = menuName;
        this.menuType = menuType;
        this.path = path;
        this.component = component;
        this.icon = icon;
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
        this.visible = visible == null || visible;
        this.permissionCode = permissionCode;
        this.status = EnableStatus.ENABLED;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
