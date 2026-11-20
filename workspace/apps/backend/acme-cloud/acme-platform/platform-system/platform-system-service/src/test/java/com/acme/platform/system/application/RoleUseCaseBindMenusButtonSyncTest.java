package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.PermissionType;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * bindMenus BUTTON 联动差量同步单元测试（REQ-20261119-051119 确认②，纯 JUnit 无 Spring）：
 * 勾选新增 / 取消删除 / 混合差量 / 非按钮来源行不动 / 无 BUTTON 节点零影响 / 码无权限点行不映射。
 * 桩镜像仓储语义（RoleAndTenantUseCaseTest 先例：闭包逐层 findAllById）。
 */
class RoleUseCaseBindMenusButtonSyncTest {

    private static final long ROLE_ID = 1L;

    /** 既有直绑权限点（非按钮来源，任何场景不得被联动触碰）。 */
    private static final long DIRECT_PERMISSION_ID = 67L;
    private static final String DIRECT_PERMISSION_CODE = "system:config:update";

    private static final long USER_MENU_ID = 2L;
    private static final long ROOT_MENU_ID = 1L;
    /** BUTTON 节点：创建用户（码 → 权限点 3）/ 删除用户（码 → 权限点 6）。 */
    private static final long BTN_CREATE_USER = 13L;
    private static final long BTN_DELETE_USER = 15L;
    private static final long PERM_CREATE_USER = 3L;
    private static final long PERM_DELETE_USER = 6L;

    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
    private final SysRoleMenuRepository roleMenuRepository = mock(SysRoleMenuRepository.class);
    private final SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
    private final SysMenuRepository menuRepository = mock(SysMenuRepository.class);

    // REQ-20261121-022910 编译兼容（签名扩展）：套餐域透传桩（真实用例 + 空仓储桩，本文件不绑
    // TenantContext → assignableMenuScope 返回 null 不受限，既有断言语义不变）
    private final RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
            rolePermissionRepository, roleMenuRepository, permissionRepository, menuRepository,
            mock(SysDeptRepository.class), mock(SysRoleDeptRepository.class),
            new TenantPackageUseCase(mock(SysTenantPackageRepository.class),
                    mock(SysTenantPackageMenuRepository.class), mock(SysTenantRepository.class),
                    mock(SysMenuRepository.class)));

    private void stubRole() {
        stubRole(false);
    }

    private void stubRole(boolean builtIn) {
        SysRole role = new SysRole("ops", "运维", null, builtIn);
        setId(role, ROLE_ID);
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
    }

    /** 请求集存在性校验 + withAncestors 闭包逐层桩（页面菜单 parent=1 系统管理目录）。 */
    private void stubRequestedMenus(SysMenu... requested) {
        Set<Long> ids = Set.of(java.util.Arrays.stream(requested).map(SysMenu::getId).toArray(Long[]::new));
        when(menuRepository.findAllById(ids)).thenReturn(List.of(requested));
        when(menuRepository.findAllById(Set.of(ROOT_MENU_ID))).thenReturn(List.of(
                menu(ROOT_MENU_ID, 0L, MenuType.DIRECTORY, null)));
    }

    private void stubButtonCatalog(SysPermission... permissions) {
        when(permissionRepository.findAll()).thenReturn(List.of(permissions));
    }

    @Test
    void should_addCheckedButtonPermission_and_keepNonButtonBinding() {
        // 勾选新增：请求 = 用户管理菜单 + 创建用户 BUTTON；既有 = 直绑 67 + 按钮来源 6（上次勾选遗留）
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        SysMenu createBtn = menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:create");
        stubRequestedMenus(userMenu, createBtn);
        stubButtonCatalog(
                permission(PERM_CREATE_USER, "system:user:create"),
                permission(PERM_DELETE_USER, "system:user:delete"),
                permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(
                menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:create"),
                menu(BTN_DELETE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:delete")));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of(
                binding(DIRECT_PERMISSION_ID), binding(PERM_DELETE_USER)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID, BTN_CREATE_USER));

        // role_menu 整体替换 + 父链补全（1/2/13）
        ArgumentCaptor<List<SysRoleMenu>> menuCaptor = ArgumentCaptor.forClass(List.class);
        verify(roleMenuRepository).deleteByRoleId(ROLE_ID);
        verify(roleMenuRepository).saveAll(menuCaptor.capture());
        assertThat(menuCaptor.getValue()).extracting(SysRoleMenu::getMenuId)
                .containsExactlyInAnyOrder(ROOT_MENU_ID, USER_MENU_ID, BTN_CREATE_USER);
        // 差量：勾选 3（新增），未勾选的按钮来源 6 删除，直绑 67 不动
        verify(rolePermissionRepository).deleteByRoleIdAndPermissionIdIn(ROLE_ID, Set.of(PERM_DELETE_USER));
        ArgumentCaptor<List<SysRolePermission>> permCaptor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(permCaptor.capture());
        assertThat(permCaptor.getValue()).extracting(SysRolePermission::getPermissionId)
                .containsExactly(PERM_CREATE_USER);
    }

    @Test
    void should_removeButtonSourcedPermissions_when_allButtonsUnchecked() {
        // 取消：请求仅页面菜单（无 BUTTON）；既有按钮来源 3 应删，直绑 67 保留
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        stubRequestedMenus(userMenu);
        stubButtonCatalog(
                permission(PERM_CREATE_USER, "system:user:create"),
                permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(
                menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:create")));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of(
                binding(DIRECT_PERMISSION_ID), binding(PERM_CREATE_USER)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID));

        verify(rolePermissionRepository).deleteByRoleIdAndPermissionIdIn(ROLE_ID, Set.of(PERM_CREATE_USER));
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void should_syncMixedDelta_when_switchingCheckedButtons() {
        // 混合：勾选切换 13→15；既有 = 3 + 直绑 67；差量 = 删 3 增 6
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        SysMenu deleteBtn = menu(BTN_DELETE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:delete");
        stubRequestedMenus(userMenu, deleteBtn);
        stubButtonCatalog(
                permission(PERM_CREATE_USER, "system:user:create"),
                permission(PERM_DELETE_USER, "system:user:delete"),
                permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(
                menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:create"),
                menu(BTN_DELETE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:delete")));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of(
                binding(DIRECT_PERMISSION_ID), binding(PERM_CREATE_USER)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID, BTN_DELETE_USER));

        verify(rolePermissionRepository).deleteByRoleIdAndPermissionIdIn(ROLE_ID, Set.of(PERM_CREATE_USER));
        ArgumentCaptor<List<SysRolePermission>> permCaptor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(permCaptor.capture());
        assertThat(permCaptor.getValue()).extracting(SysRolePermission::getPermissionId)
                .containsExactly(PERM_DELETE_USER);
    }

    @Test
    void should_touchNothing_when_roleHoldsOnlyNonButtonBindings() {
        // 非按钮不动：仅直绑 67，请求无 BUTTON → 无删无增（识别域与 present 无交集）
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        stubRequestedMenus(userMenu);
        stubButtonCatalog(
                permission(PERM_CREATE_USER, "system:user:create"),
                permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(
                menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:create")));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of(binding(DIRECT_PERMISSION_ID)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID));

        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionIdIn(any(), anyCollection());
        verify(rolePermissionRepository, never()).saveAll(any());
        // role_menu 替换本身照常发生
        verify(roleMenuRepository).deleteByRoleId(ROLE_ID);
    }

    @Test
    void should_skipSyncEntirely_when_noButtonNodesExist() {
        // 无 BUTTON 节点零影响：全平台无 BUTTON（存量库升级前）→ 联动早退，权限仓储零交互
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        stubRequestedMenus(userMenu);
        stubButtonCatalog(permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(userMenu,
                menu(ROOT_MENU_ID, 0L, MenuType.DIRECTORY, null)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID));

        verify(rolePermissionRepository, never()).findByRoleId(any());
        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionIdIn(any(), anyCollection());
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void should_notMapUnresolvableButtonCode_when_permissionRowMissing() {
        // 数据漂移兜底：BUTTON 码在权限点目录无对应行 → 不映射、不产生绑定行
        stubRole();
        SysMenu userMenu = menu(USER_MENU_ID, ROOT_MENU_ID, MenuType.MENU, "system:user:list");
        SysMenu ghostBtn = menu(BTN_CREATE_USER, USER_MENU_ID, MenuType.BUTTON, "system:user:ghost");
        stubRequestedMenus(userMenu, ghostBtn);
        stubButtonCatalog(permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(ghostBtn));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of(binding(DIRECT_PERMISSION_ID)));

        useCase.bindMenus(ROLE_ID, List.of(USER_MENU_ID, BTN_CREATE_USER));

        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionIdIn(any(), anyCollection());
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void should_rejectWholeRequestWithoutWrites_when_nonBuiltInRoleChecksReservedButton() {
        // 保留域守卫（与 bindPermissions 同口径）：非内置角色勾选保留域码 BUTTON（system:tenant:update
        // → V20 节点 29）整体拒绝，role_menu / role_permission 均零写入
        stubRole(false);
        SysMenu tenantMenu = menu(7L, ROOT_MENU_ID, MenuType.MENU, "system:tenant:list");
        SysMenu tenantBtn = menu(29L, 7L, MenuType.BUTTON, "system:tenant:update");
        stubRequestedMenus(tenantMenu, tenantBtn);
        stubButtonCatalog(permission(31L, "system:tenant:update"),
                permission(DIRECT_PERMISSION_ID, DIRECT_PERMISSION_CODE));
        when(menuRepository.findAll()).thenReturn(List.of(tenantBtn));

        assertThatThrownBy(() -> useCase.bindMenus(ROLE_ID, List.of(7L, 29L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_GRANT_RESERVED.code()));
        verify(roleMenuRepository, never()).deleteByRoleId(any());
        verify(roleMenuRepository, never()).saveAll(any());
        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionIdIn(any(), anyCollection());
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void should_allowReservedButtonSync_when_builtInRoleChecksReservedButton() {
        // 内置角色豁免（与 bindPermissions 一致）：内置角色勾选保留域 BUTTON → 联动照常授予
        stubRole(true);
        SysMenu tenantMenu = menu(7L, ROOT_MENU_ID, MenuType.MENU, "system:tenant:list");
        SysMenu tenantBtn = menu(29L, 7L, MenuType.BUTTON, "system:tenant:update");
        stubRequestedMenus(tenantMenu, tenantBtn);
        stubButtonCatalog(permission(31L, "system:tenant:update"));
        when(menuRepository.findAll()).thenReturn(List.of(tenantBtn));
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of());

        useCase.bindMenus(ROLE_ID, List.of(7L, 29L));

        ArgumentCaptor<List<SysRolePermission>> permCaptor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(permCaptor.capture());
        assertThat(permCaptor.getValue()).extracting(SysRolePermission::getPermissionId)
                .containsExactly(31L);
        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionIdIn(any(), anyCollection());
    }

    // ---------- 桩工厂 ----------

    private static SysMenu menu(long id, long parentId, MenuType type, String permissionCode) {
        SysMenu menu = new SysMenu(parentId, "菜单" + id, type, null, null, null, 0, true, permissionCode);
        setId(menu, id);
        return menu;
    }

    private static SysPermission permission(long id, String code) {
        SysPermission permission = new SysPermission(code, code, PermissionType.API, "GET", "/x");
        setId(permission, id);
        return permission;
    }

    private static SysRolePermission binding(long permissionId) {
        SysRolePermission binding = new SysRolePermission(ROLE_ID, permissionId);
        setId(binding, 100L + permissionId);
        return binding;
    }

    /** 种子/桩实体 id 反射注入（AuditBase id 无 setter，RoleAndTenantUseCaseTest 先例）。 */
    private static void setId(Object entity, long id) {
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
