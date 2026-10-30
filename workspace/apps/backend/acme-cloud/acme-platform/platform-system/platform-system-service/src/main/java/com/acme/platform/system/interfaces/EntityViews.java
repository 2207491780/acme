package com.acme.platform.system.interfaces;

import com.acme.common.util.TreeBuilder;
import com.acme.common.util.TreeNode;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.api.dto.AdminDictItemView;
import com.acme.platform.system.api.dto.AdminDictTypeView;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.api.dto.AdminPermissionView;
import com.acme.platform.system.api.dto.AdminRoleView;
import com.acme.platform.system.api.dto.AdminTenantView;
import com.acme.platform.system.api.dto.AdminUserView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体 → 视图转换（11.5.3：转换在接口层完成，服务间 DTO 不直接作为对外响应）。
 */
public final class EntityViews {

    private EntityViews() {
    }

    /** 用户视图（含绑定角色集）。 */
    public static AdminUserView userView(SysUser user, List<Long> roleIds) {
        return new AdminUserView(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhone(), user.getStatus(), user.getBuiltIn(), roleIds,
                user.getCreatedAt(), user.getUpdatedAt(), user.getVersion());
    }

    public static AdminRoleView roleView(SysRole role) {
        return new AdminRoleView(role.getId(), role.getParentId(), role.getRoleCode(), role.getRoleName(),
                role.getStatus(), role.getBuiltIn(), role.getRemark(), role.getCreatedAt(), List.of(),
                role.getVersion());
    }

    /** 角色树（根 parentId=0 归一 null 供 TreeBuilder 组装；环 / 重复 id / 孤儿显式失败，与 menuTree 同款）。 */
    public static List<AdminRoleView> roleTree(List<SysRole> allRoles) {
        List<TreeNode<SysRole>> roots = TreeBuilder.build(allRoles,
                SysRole::getId,
                role -> role.getParentId() == SysRole.ROOT_PARENT_ID ? null : role.getParentId());
        return mapRoleNodes(roots);
    }

    private static List<AdminRoleView> mapRoleNodes(List<TreeNode<SysRole>> nodes) {
        List<AdminRoleView> views = new ArrayList<>();
        for (TreeNode<SysRole> node : nodes) {
            SysRole role = node.payload();
            views.add(new AdminRoleView(role.getId(), role.getParentId(), role.getRoleCode(), role.getRoleName(),
                    role.getStatus(), role.getBuiltIn(), role.getRemark(), role.getCreatedAt(),
                    mapRoleNodes(node.children()), role.getVersion()));
        }
        return views;
    }

    public static AdminPermissionView permissionView(SysPermission permission) {
        return new AdminPermissionView(permission.getId(), permission.getPermissionCode(),
                permission.getPermissionName(), permission.getPermissionType(), permission.getHttpMethod(),
                permission.getHttpPath(), permission.getStatus(), permission.getCreatedAt(),
                permission.getVersion());
    }

    public static AdminTenantView tenantView(SysTenant tenant) {
        return new AdminTenantView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                tenant.getStatus(), tenant.getRemark(), tenant.getCreatedAt(), tenant.getVersion());
    }

    public static AdminDictTypeView dictTypeView(SysDictType type) {
        return new AdminDictTypeView(type.getId(), type.getDictCode(), type.getDictName(), type.getStatus(),
                type.getRemark(), type.getCreatedAt(), type.getVersion());
    }

    public static AdminDictItemView dictItemView(SysDictItem item) {
        return new AdminDictItemView(item.getId(), item.getDictTypeId(), item.getItemLabel(),
                item.getItemValue(), item.getSortOrder(), item.getStatus(), item.getVersion());
    }

    /** 菜单平铺视图（无 children，详情 / 创建 / 修改响应用）。 */
    public static AdminMenuView flatMenu(SysMenu menu) {
        return new AdminMenuView(menu.getId(), menu.getParentId(), menu.getMenuName(), menu.getMenuType(),
                menu.getPath(), menu.getComponent(), menu.getIcon(), menu.getSortOrder(), menu.getVisible(),
                menu.getPermissionCode(), menu.getStatus(), List.of(), menu.getVersion());
    }

    /** 菜单树（可见集过滤 + 父链自动补全，容忍绑定数据缺父链；根 parentId=0 归一 null 供 TreeBuilder 组装）。 */
    public static List<AdminMenuView> menuTree(List<SysMenu> allMenus, Set<Long> includeIds) {
        if (includeIds != null && !includeIds.isEmpty()) {
            includeIds = withAncestors(allMenus, includeIds);
        }
        Set<Long> finalInclude = includeIds;
        List<SysMenu> filtered = finalInclude == null ? allMenus : allMenus.stream()
                .filter(menu -> finalInclude.contains(menu.getId()))
                .toList();
        List<TreeNode<SysMenu>> roots = TreeBuilder.build(filtered,
                menu -> menu.getId(),
                menu -> menu.getParentId() == SysMenu.ROOT_PARENT_ID ? null : menu.getParentId());
        return mapNodes(roots);
    }

    /** 可见集父链补全（父节点不存在于全量目录时止步，不扩散异常）。 */
    private static Set<Long> withAncestors(List<SysMenu> allMenus, Set<Long> includeIds) {
        Map<Long, SysMenu> byId = new HashMap<>();
        allMenus.forEach(menu -> byId.put(menu.getId(), menu));
        Set<Long> closure = new LinkedHashSet<>(includeIds);
        for (Long menuId : includeIds) {
            Long parentId = byId.containsKey(menuId) ? byId.get(menuId).getParentId() : null;
            while (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && !closure.contains(parentId)
                    && byId.containsKey(parentId)) {
                closure.add(parentId);
                parentId = byId.get(parentId).getParentId();
            }
        }
        return closure;
    }

    private static List<AdminMenuView> mapNodes(List<TreeNode<SysMenu>> nodes) {
        List<AdminMenuView> views = new ArrayList<>();
        for (TreeNode<SysMenu> node : nodes) {
            SysMenu menu = node.payload();
            views.add(new AdminMenuView(menu.getId(), menu.getParentId(), menu.getMenuName(),
                    menu.getMenuType(), menu.getPath(), menu.getComponent(), menu.getIcon(),
                    menu.getSortOrder(), menu.getVisible(), menu.getPermissionCode(), menu.getStatus(),
                    mapNodes(node.children()), menu.getVersion()));
        }
        return views;
    }
}
