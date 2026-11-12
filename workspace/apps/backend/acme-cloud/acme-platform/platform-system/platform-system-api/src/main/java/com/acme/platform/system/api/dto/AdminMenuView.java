package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单节点视图（树形，children 嵌套；根节点 parentId=0）。
 */
public record AdminMenuView(Long id, Long parentId, String menuName, MenuType menuType,
                            String path, String component, String icon, Integer sortOrder, Boolean visible,
                            String permissionCode, EnableStatus status, List<AdminMenuView> children, Long version) {

    public AdminMenuView {
        children = children == null ? List.of() : new ArrayList<>(children);
    }
}
