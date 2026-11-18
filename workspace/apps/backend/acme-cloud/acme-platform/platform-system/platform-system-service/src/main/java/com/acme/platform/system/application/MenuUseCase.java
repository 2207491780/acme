package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminMenuCreate;
import com.acme.platform.system.api.dto.AdminMenuUpdate;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单管理用例（FR-008 菜单级权限，架构设计 5.1 菜单行；平台级目录树，parent_id 组装无对象关联 7.2.5）。
 */
@Service
public class MenuUseCase {

    private final SysMenuRepository menuRepository;
    private final SysRoleMenuRepository roleMenuRepository;

    public MenuUseCase(SysMenuRepository menuRepository, SysRoleMenuRepository roleMenuRepository) {
        this.menuRepository = menuRepository;
        this.roleMenuRepository = roleMenuRepository;
    }

    @Transactional
    public SysMenu create(AdminMenuCreate request) {
        Long parentId = request.parentId() == null ? SysMenu.ROOT_PARENT_ID : request.parentId();
        if (parentId != SysMenu.ROOT_PARENT_ID && !menuRepository.existsById(parentId)) {
            throw new BizException(SystemError.MENU_NOT_FOUND);
        }
        return menuRepository.save(new SysMenu(parentId, request.menuName(), request.menuType(),
                request.path(), request.component(), request.icon(), request.sortOrder(),
                request.visible(), request.permissionCode()));
    }

    public SysMenu get(Long menuId) {
        return menuRepository.findById(menuId).orElseThrow(() -> new BizException(SystemError.MENU_NOT_FOUND));
    }

    @Transactional
    public SysMenu update(Long menuId, AdminMenuUpdate request) {
        SysMenu menu = get(menuId);
        if (request.version() == null || !request.version().equals(menu.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        menu.setMenuName(request.menuName());
        menu.setPath(request.path());
        menu.setComponent(request.component());
        menu.setIcon(request.icon());
        menu.setSortOrder(request.sortOrder());
        menu.setVisible(request.visible());
        menu.setPermissionCode(request.permissionCode());
        return menuRepository.save(menu);
    }

    /** 软删除（存在子菜单或角色绑定拒绝；幂等）。 */
    @Transactional
    public void delete(Long menuId) {
        menuRepository.findById(menuId).ifPresent(menu -> {
            if (menuRepository.existsByParentId(menuId)) {
                throw new BizException(SystemError.MENU_DELETE_DENIED);
            }
            if (roleMenuRepository.existsByMenuId(menuId)) {
                throw new BizException(SystemError.MENU_DELETE_DENIED);
            }
            menuRepository.delete(menu);
        });
    }

    /** 全量菜单（平台目录，按 sortOrder 排序）。 */
    public List<SysMenu> listAll() {
        return menuRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    /** 角色集可见菜单并集（含父链补全，/me 视图用）。 */
    public Set<Long> visibleMenuIdsOf(List<Long> roleIds) {
        Set<Long> visible = new LinkedHashSet<>();
        if (roleIds != null && !roleIds.isEmpty()) {
            roleMenuRepository.findByRoleIdIn(roleIds).forEach(binding -> visible.add(binding.getMenuId()));
        }
        return visible;
    }

    /**
     * 内置角色集可见菜单并集（REQ-20261117-025200 切换会话 /me 用）：跨租户旁路读取 role_menu
     * 绑定行（内置超管绑定行归属源租户，确认记录④切换后目标租户上下文按内置角色 id 取绑定）。
     * 调用方契约：仅限传入 {@code findBuiltInByRoleCodeIn} 命中的内置角色 id 集（雪花 id 全局唯一，
     * 按内置角色 id 取绑定行不扩大信任面；非内置角色 id 集不得进入本旁路面，BR-017 评审约束），
     * 旁路经 {@code @IgnoreTenant} 显式声明并记跨租户审计 accessPoint。
     */
    @com.acme.framework.tenant.support.IgnoreTenant("built-in-role-menu-cross-tenant-lookup")
    public Set<Long> visibleMenuIdsOfBuiltIn(List<Long> builtInRoleIds) {
        return visibleMenuIdsOf(builtInRoleIds);
    }
}
