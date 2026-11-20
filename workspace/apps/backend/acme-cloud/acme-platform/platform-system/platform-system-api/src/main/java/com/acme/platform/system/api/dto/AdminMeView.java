package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 当前登录者视图（FR-008 权限模型支撑的查询面）：权限声明与菜单可见性依据，
 * 供后续前端菜单渲染与按钮控制消费（菜单级权限）。
 * REQ-20261117-180000：deptName=本人部门名（水印模板 {deptName} 供给；未分配部门/部门行缺失=null）。
 * REQ-20261121-054613 确认⑤：capabilities=当前租户生效能力码（web 特性开关数据源；
 * 未绑定套餐=全部启用能力码；TenantContext 缺失=空数组 fail-closed）。
 */
public record AdminMeView(AdminUserView user, List<String> roles, List<String> permissions,
                          List<AdminMenuView> menus, String deptName, List<String> capabilities) {

    public AdminMeView {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        menus = menus == null ? List.of() : List.copyOf(menus);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
