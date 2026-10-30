package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 当前登录者视图（FR-008 权限模型支撑的查询面）：权限声明与菜单可见性依据，
 * 供后续前端菜单渲染与按钮控制消费（菜单级权限）。
 */
public record AdminMeView(AdminUserView user, List<String> roles, List<String> permissions,
                          List<AdminMenuView> menus) {

    public AdminMeView {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        menus = menus == null ? List.of() : List.copyOf(menus);
    }
}
