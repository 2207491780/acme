package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 菜单（7.2.5：平台级目录表，无 tenant_id、不参与租户过滤——Q-D04；树形经 parentId + TreeBuilder 组装，
 * 无 JPA 对象关联；@SoftDelete 软删除）。
 */
@Entity
@SoftDelete
@Table(name = "sys_menu", indexes = @Index(name = "idx_sys_menu_parent", columnList = "parent_id"))
public class SysMenu extends AuditBase {

    /** 根节点 parentId 哨兵（无 JPA 关联，树组装时映射为 null）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "menu_name", length = 64, nullable = false)
    private String menuName;

    @Enumerated(EnumType.STRING)
    @Column(name = "menu_type", length = 16, nullable = false)
    private MenuType menuType;

    @Column(name = "path", length = 255)
    private String path;

    @Column(name = "component", length = 255)
    private String component;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "visible", nullable = false)
    private Boolean visible;

    @Column(name = "permission_code", length = 128)
    private String permissionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
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
