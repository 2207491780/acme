package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.PermissionType;
import com.acme.platform.system.api.dto.AdminDictItemCreate;
import com.acme.platform.system.api.dto.AdminDictItemUpdate;
import com.acme.platform.system.api.dto.AdminDictTypeCreate;
import com.acme.platform.system.api.dto.AdminDictTypeUpdate;
import com.acme.platform.system.api.dto.AdminMeView;
import com.acme.platform.system.api.dto.AdminMenuCreate;
import com.acme.platform.system.api.dto.AdminMenuIds;
import com.acme.platform.system.api.dto.AdminMenuUpdate;
import com.acme.platform.system.api.dto.AdminPermissionCreate;
import com.acme.platform.system.api.dto.AdminPermissionIds;
import com.acme.platform.system.api.dto.AdminPermissionUpdate;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleIds;
import com.acme.platform.system.api.dto.AdminRoleUpdate;
import com.acme.platform.system.api.dto.AdminRoleView;
import com.acme.platform.system.api.dto.AdminStatusUpdate;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.application.AuthProfileUseCase;
import com.acme.platform.system.application.AuthorizationUseCase;
import com.acme.platform.system.application.DictUseCase;
import com.acme.platform.system.application.MeUseCase;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.application.PermissionUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.application.TenantInitUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.application.UserUseCase;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口层全表面直调测试（10.1 接口层：参数校验、视图转换、权限标注；视图组装 EntityViews 全量覆盖）。
 * 用例打桩，不经 HTTP（HTTP 切片由集成测试承担）。
 */
class SystemApiSurfaceTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void userControllerFullSurface() {
        UserUseCase useCase = mock(UserUseCase.class);
        SysUser user = new SysUser("u1", "昵称", "e@acme.com", "138", false);
        when(useCase.create(any())).thenReturn(user);
        when(useCase.get(any())).thenReturn(user);
        when(useCase.update(any(), any())).thenReturn(user);
        when(useCase.page(any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(user), 1L));
        when(useCase.roleIdsOf(any())).thenReturn(List.of(3L));
        UserController controller = new UserController(useCase);

        assertThat(controller.list(1, 20, null, null).total()).isEqualTo(1L);
        assertThat(controller.create(new AdminUserCreate("u1", "昵称", null, null, null)).username())
                .isEqualTo("u1");
        assertThat(controller.get(7L).roleIds()).containsExactly(3L);
        assertThat(controller.update(7L, new AdminUserUpdate("昵称", null, null, 1L)).nickname()).isEqualTo("昵称");
        controller.delete(7L);
        controller.updateStatus(7L, new AdminStatusUpdate(EnableStatus.DISABLED));
        controller.bindRoles(7L, new AdminRoleIds(List.of(3L)));
    }

    // 菜单树组装需实体 id（TreeBuilder DP-004 非空约定）
    private static SysMenu menuWithId(long id, long parentId, String name, MenuType type) {
        SysMenu menu = new SysMenu(parentId, name, type, null, null, null, 0, true, null);
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(menu, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return menu;
    }

    // 角色树组装同理需实体 id
    private static SysRole roleWithId(long id, long parentId, String code) {
        SysRole role = new SysRole(parentId, code, code, null, false);
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return role;
    }

@Test
    void roleControllerFullSurface() {
        RoleUseCase roleUseCase = mock(RoleUseCase.class);
        PermissionUseCase permissionUseCase = mock(PermissionUseCase.class);
        MenuUseCase menuUseCase = mock(MenuUseCase.class);
        SysRole role = new SysRole("ops", "运维", null, false);
        when(roleUseCase.create(any())).thenReturn(role);
        when(roleUseCase.get(any())).thenReturn(role);
        when(roleUseCase.update(any(), any())).thenReturn(role);
        when(roleUseCase.page(any(), any())).thenReturn(new PageResult<>(List.of(role), 1L));
        when(roleUseCase.permissionIdsOf(any())).thenReturn(List.of(9L));
        when(roleUseCase.menuIdsOf(any())).thenReturn(List.of(1L));
        when(roleUseCase.listAll()).thenReturn(List.of(
                roleWithId(1L, 0L, "ops"), roleWithId(2L, 1L, "ops_child")));
        when(permissionUseCase.listByIds(any())).thenReturn(List.of(
                new SysPermission("system:user:list", "用户列表", PermissionType.API, "GET", "/x")));
        when(menuUseCase.listAll()).thenReturn(List.of(menuWithId(1L, 0L, "系统管理", MenuType.DIRECTORY)));
        RoleController controller = new RoleController(roleUseCase, permissionUseCase, menuUseCase);

        assertThat(controller.list(1, 20, null).total()).isEqualTo(1L);
        assertThat(controller.create(new AdminRoleCreate(null, "ops", "运维", null)).roleCode()).isEqualTo("ops");
        assertThat(controller.get(1L).roleName()).isEqualTo("运维");
        assertThat(controller.update(1L, new AdminRoleUpdate("运维", null, null, 1L)).roleCode()).isEqualTo("ops");
        var tree = controller.tree();
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).parentId()).isEqualTo(0L);
        assertThat(tree.get(0).children()).extracting(AdminRoleView::roleCode).containsExactly("ops_child");
        controller.delete(1L);
        controller.updateStatus(1L, new AdminStatusUpdate(EnableStatus.ENABLED));
        assertThat(controller.permissions(1L)).hasSize(1);
        controller.bindPermissions(1L, new AdminPermissionIds(List.of(9L)));
        assertThat(controller.menus(1L)).hasSize(1);
        controller.bindMenus(1L, new AdminMenuIds(List.of(2L)));
    }

    @Test
    void permissionControllerFullSurface() {
        PermissionUseCase useCase = mock(PermissionUseCase.class);
        SysPermission permission = new SysPermission("system:user:create", "创建用户",
                PermissionType.API, "POST", "/y");
        when(useCase.create(any())).thenReturn(permission);
        when(useCase.get(any())).thenReturn(permission);
        when(useCase.update(any(), any())).thenReturn(permission);
        when(useCase.page(any(), any())).thenReturn(new PageResult<>(List.of(permission), 1L));
        PermissionController controller = new PermissionController(useCase);

        assertThat(controller.list(1, 20, null).total()).isEqualTo(1L);
        assertThat(controller.create(new AdminPermissionCreate("system:user:create", "创建用户",
                PermissionType.API, "POST", "/y")).permissionCode()).isEqualTo("system:user:create");
        assertThat(controller.get(9L).permissionName()).isEqualTo("创建用户");
        assertThat(controller.update(9L, new AdminPermissionUpdate("创建用户", null, null, 1L))
                .permissionCode()).isEqualTo("system:user:create");
        controller.delete(9L);
    }

    @Test
    void menuControllerFullSurface() {
        MenuUseCase useCase = mock(MenuUseCase.class);
        SysMenu menu = menuWithId(1L, 0L, "系统管理", MenuType.DIRECTORY);
        when(useCase.create(any())).thenReturn(menu);
        when(useCase.get(any())).thenReturn(menu);
        when(useCase.update(any(), any())).thenReturn(menu);
        when(useCase.listAll()).thenReturn(List.of(menu));
        MenuController controller = new MenuController(useCase);

        assertThat(controller.tree()).hasSize(1);
        assertThat(controller.create(new AdminMenuCreate(0L, "系统管理", MenuType.DIRECTORY,
                "/system", null, null, 0, true, null)).menuName()).isEqualTo("系统管理");
        assertThat(controller.get(1L).menuName()).isEqualTo("系统管理");
        assertThat(controller.update(1L, new AdminMenuUpdate("系统管理", null, null, null, 0, true,
                null, 1L)).menuName()).isEqualTo("系统管理");
        controller.delete(1L);
    }

    @Test
    void dictControllerFullSurface() {
        DictUseCase useCase = mock(DictUseCase.class);
        SysDictType type = new SysDictType("gender", "性别", null);
        SysDictItem item = new SysDictItem(type, "男", "M", 0);
        when(useCase.createType(any())).thenReturn(type);
        when(useCase.getType(any())).thenReturn(type);
        when(useCase.updateType(any(), any())).thenReturn(type);
        when(useCase.pageTypes(any(), any())).thenReturn(new PageResult<>(List.of(type), 1L));
        when(useCase.createItem(any(), any())).thenReturn(item);
        when(useCase.getItem(any())).thenReturn(item);
        when(useCase.updateItem(any(), any())).thenReturn(item);
        when(useCase.listItems(any())).thenReturn(List.of(item));
        DictController controller = new DictController(useCase);

        assertThat(controller.listTypes(1, 20, null).total()).isEqualTo(1L);
        assertThat(controller.createType(new AdminDictTypeCreate("gender", "性别", null)).dictCode())
                .isEqualTo("gender");
        assertThat(controller.updateType(1L, new AdminDictTypeUpdate("性别", null, 1L)).dictCode())
                .isEqualTo("gender");
        controller.deleteType(1L);
        assertThat(controller.listItems(1L)).hasSize(1);
        assertThat(controller.createItem(1L, new AdminDictItemCreate("男", "M", 0)).itemValue()).isEqualTo("M");
        assertThat(controller.updateItem(2L, new AdminDictItemUpdate("男", "M", 0, 1L)).itemLabel()).isEqualTo("男");
        controller.deleteItem(2L);
    }

    @Test
    void tenantControllerFullSurface() {
        TenantUseCase useCase = mock(TenantUseCase.class);
        TenantInitUseCase initUseCase = mock(TenantInitUseCase.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        when(useCase.create(any())).thenReturn(tenant);
        when(useCase.get(any())).thenReturn(tenant);
        when(useCase.update(any(), any())).thenReturn(tenant);
        when(useCase.page(any(), any())).thenReturn(new PageResult<>(List.of(tenant), 1L));
        when(initUseCase.initialize(any())).thenReturn(new com.acme.platform.system.api.dto.AdminTenantInitView(
                9L, "acme", "Acme 租户", 99L, "acme_admin", "租户管理员"));
        TenantController controller = new TenantController(useCase, initUseCase);

        assertThat(controller.list(1, 20, null).total()).isEqualTo(1L);
        assertThat(controller.create(new AdminTenantCreate("acme", "Acme 租户", null)).tenantCode())
                .isEqualTo("acme");
        assertThat(controller.get(2L).tenantName()).isEqualTo("Acme 租户");
        assertThat(controller.update(2L, new AdminTenantUpdate("Acme 租户", null, 1L)).tenantCode())
                .isEqualTo("acme");
        controller.updateStatus(2L, new AdminStatusUpdate(EnableStatus.DISABLED));
        // 开通初始化端点（tenant-management-complete）
        assertThat(controller.initialize(new com.acme.platform.system.api.dto.AdminTenantInitRequest(
                "acme", "Acme 租户", null, "acme_admin", "租户管理员")).adminUserId()).isEqualTo(99L);
    }

    @Test
    void meControllerAssemblesSnapshot() {
        MeUseCase useCase = mock(MeUseCase.class);
        MenuUseCase menuUseCase = mock(MenuUseCase.class);
        SysUser user = new SysUser("admin", "超级管理员", null, null, true);
        when(useCase.loadMe()).thenReturn(new MeUseCase.MeSnapshot(user,
                java.util.Set.of("super_admin"), java.util.Set.of("system:user:list"),
                java.util.Set.of(1L, 2L)));
        when(menuUseCase.listAll()).thenReturn(List.of(
                menuWithId(1L, 0L, "系统管理", MenuType.DIRECTORY),
                menuWithId(2L, 1L, "用户管理", MenuType.MENU)));
        MeController controller = new MeController(useCase, menuUseCase);
        AdminMeView me = controller.me();
        assertThat(me.user().username()).isEqualTo("admin");
        assertThat(me.roles()).containsExactly("super_admin");
        assertThat(me.permissions()).containsExactly("system:user:list");
        assertThat(me.menus()).hasSize(1);
        assertThat(me.menus().get(0).children()).hasSize(1);
    }

    @Test
    void internalAuthProfileControllerReturnsEnvelope() {
        AuthProfileUseCase useCase = mock(AuthProfileUseCase.class);
        when(useCase.findByUsername(anyString())).thenReturn(
                new com.acme.platform.system.api.AuthProfileView("1", "admin", "超级管理员",
                        "default", "ENABLED", java.util.Set.of("super_admin")));
        AuthProfileController controller = new AuthProfileController(useCase);
        var result = controller.findByUsername("admin");
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData().userId()).isEqualTo("1");
    }

    @Test
    void internalAuthorizationControllerReturnsDecision() {
        AuthorizationUseCase useCase = mock(AuthorizationUseCase.class);
        when(useCase.hasPermission(anyString())).thenReturn(true);
        AuthorizationController controller = new AuthorizationController(useCase);
        assertThat(controller.hasPermission("auth:credential:manage").getData()).isTrue();
    }

    @Test
    void entityViewsMappersCoverAllAggregates() {
        // 视图组装器全量映射（11.5.3 接口层转换面）
        SysUser user = new SysUser("u", "昵称", null, null, false);
        assertThat(EntityViews.userView(user, List.of(1L)).username()).isEqualTo("u");
        SysRole role = new SysRole("ops", "运维", null, false);
        assertThat(EntityViews.roleView(role).roleCode()).isEqualTo("ops");
        assertThat(EntityViews.roleView(role).parentId()).isEqualTo(SysRole.ROOT_PARENT_ID);
        assertThat(EntityViews.roleTree(List.of(roleWithId(1L, 0L, "ops")))).hasSize(1);
        SysPermission permission = new SysPermission("system:user:create", "创建用户",
                PermissionType.API, "POST", "/y");
        assertThat(EntityViews.permissionView(permission).permissionType()).isEqualTo(PermissionType.API);
        SysTenant tenant = new SysTenant("acme", "Acme", null);
        assertThat(EntityViews.tenantView(tenant).tenantCode()).isEqualTo("acme");
        SysDictType type = new SysDictType("gender", "性别", null);
        assertThat(EntityViews.dictTypeView(type).dictCode()).isEqualTo("gender");
        SysDictItem item = new SysDictItem(type, "男", "M", 0);
        assertThat(EntityViews.dictItemView(item).itemValue()).isEqualTo("M");
        SysMenu menu = menuWithId(1L, 0L, "系统管理", MenuType.DIRECTORY);
        assertThat(EntityViews.flatMenu(menu).parentId()).isEqualTo(0L);
        assertThat(EntityViews.menuTree(List.of(menu), null)).hasSize(1);
        assertThat(EntityViews.menuTree(List.of(menu), java.util.Set.of())).isEmpty();
    }

    @Test
    void pageQueryDefaultsFromRequestParams() {
        // 分页基元缺省口径（common-contract PageQuery）
        PageQuery query = new PageQuery(null, null);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
    }
}
