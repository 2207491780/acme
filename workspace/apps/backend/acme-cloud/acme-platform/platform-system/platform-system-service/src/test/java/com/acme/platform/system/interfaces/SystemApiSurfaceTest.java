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
import com.acme.platform.system.api.dto.AdminSystemConfigUpdate;
import com.acme.platform.system.api.dto.AdminSystemConfigView;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.application.AuthProfileUseCase;
import com.acme.platform.system.application.AuthorizationUseCase;
import com.acme.platform.system.application.DictUseCase;
import com.acme.platform.system.application.DeptUseCase;
import com.acme.platform.system.application.MeUseCase;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.application.PermissionUseCase;
import com.acme.platform.system.application.PositionUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.application.SystemConfigUseCase;
import com.acme.platform.system.application.TenantInitUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.application.UserUseCase;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysPosition;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.api.dto.AdminDeptView;
import org.junit.jupiter.api.AfterEach;
import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.flex.support.FlexError;
import com.acme.framework.web.interceptor.GlobalExceptionTranslator;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Optional;

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
        when(useCase.page(any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(user), 1L));
        when(useCase.roleIdsOf(any())).thenReturn(List.of(3L));
        UserController controller = new UserController(useCase);

        assertThat(controller.list(1, 20, null, null, null).total()).isEqualTo(1L);
        assertThat(controller.create(new AdminUserCreate("u1", "昵称", null, null, null)).username())
                .isEqualTo("u1");
        assertThat(controller.get(7L).roleIds()).containsExactly(3L);
        assertThat(controller.update(7L, new AdminUserUpdate("昵称", null, null, 1L)).nickname()).isEqualTo("昵称");
        controller.delete(7L);
        controller.updateStatus(7L, new AdminStatusUpdate(EnableStatus.DISABLED));
        controller.bindRoles(7L, new AdminRoleIds(List.of(3L)));
    }

    @Test
    void deptControllerFullSurface() {
        // REQ-20261114-120104 部门接口全表面（树 / 增改删）
        DeptUseCase useCase = mock(DeptUseCase.class);
        SysDept dept = deptWithId(1L, 0L, "总部");
        when(useCase.create(any())).thenReturn(dept);
        when(useCase.get(any())).thenReturn(dept);
        // update 桩镜像真实用例语义：把请求应用到实体再返回（否则静态桩返回未变更实体）
        when(useCase.update(any(), any())).thenAnswer(invocation -> {
            com.acme.platform.system.api.dto.AdminDeptUpdate request = invocation.getArgument(1);
            dept.setDeptName(request.name());
            if (request.sortOrder() != null) {
                dept.setSortOrder(request.sortOrder());
            }
            if (request.status() != null) {
                dept.setStatus(request.status());
            }
            return dept;
        });
        when(useCase.listAll()).thenReturn(List.of(dept));
        DeptController controller = new DeptController(useCase);

        assertThat(controller.tree()).hasSize(1);
        assertThat(controller.create(new com.acme.platform.system.api.dto.AdminDeptCreate(
                0L, "总部", 1, EnableStatus.ENABLED)).deptName()).isEqualTo("总部");
        assertThat(controller.get(1L).parentId()).isEqualTo(0L);
        assertThat(controller.update(1L, new com.acme.platform.system.api.dto.AdminDeptUpdate(
                "总部二", null, 2, EnableStatus.DISABLED, 0L)).sortOrder()).isEqualTo(2);
        controller.delete(1L);
    }

    @Test
    void positionControllerFullSurface() {
        // REQ-20261114-120104 岗位接口全表面（分页 / 增改删 / 启停）
        PositionUseCase useCase = mock(PositionUseCase.class);
        SysPosition position = new SysPosition("engineer", "工程师", EnableStatus.ENABLED);
        when(useCase.create(any())).thenReturn(position);
        when(useCase.get(any())).thenReturn(position);
        // update 桩镜像真实用例语义：把请求应用到实体再返回（否则静态桩返回未变更实体）
        when(useCase.update(any(), any())).thenAnswer(invocation -> {
            com.acme.platform.system.api.dto.AdminPositionUpdate request = invocation.getArgument(1);
            position.setPositionCode(request.code());
            position.setPositionName(request.name());
            if (request.status() != null) {
                position.setStatus(request.status());
            }
            return position;
        });
        when(useCase.page(any(), any(), any())).thenReturn(new PageResult<>(List.of(position), 1L));
        PositionController controller = new PositionController(useCase);

        assertThat(controller.list(1, 20, null, null).total()).isEqualTo(1L);
        assertThat(controller.create(new com.acme.platform.system.api.dto.AdminPositionCreate(
                "engineer", "工程师", EnableStatus.ENABLED)).positionCode()).isEqualTo("engineer");
        assertThat(controller.get(9L).positionName()).isEqualTo("工程师");
        assertThat(controller.update(9L, new com.acme.platform.system.api.dto.AdminPositionUpdate(
                "engineer", "高级工程师", EnableStatus.ENABLED, 0L)).positionName()).isEqualTo("高级工程师");
        controller.updateStatus(9L, new AdminStatusUpdate(EnableStatus.DISABLED));
        controller.delete(9L);
    }

    @Test
    void systemConfigControllerFullSurface() throws Exception {
        // REQ-20261116-230354 系统配置接口全表面（GET 缺省/有行 + PUT 委托 + 权限标注口径）
        SystemConfigUseCase useCase = mock(SystemConfigUseCase.class);
        SysConfig config = new SysConfig(false, true, 60_000);
        config.setVersion(3L);
        when(useCase.findCurrent()).thenReturn(Optional.of(config));
        when(useCase.update(any())).thenReturn(config);
        SystemConfigController controller = new SystemConfigController(useCase, mock(GlobalExceptionTranslator.class));

        assertThat(controller.get().watermarkEnabled()).isFalse();
        assertThat(controller.get().captchaEnabled()).isTrue();
        assertThat(controller.get().lockIdleTimeoutMs()).isEqualTo(60_000);
        assertThat(controller.get().version()).isEqualTo(3L);
        // REQ-20261117-025200：有行视图页脚两字段透传（行缺省 null/false）
        assertThat(controller.get().footerCopyright()).isNull();
        assertThat(controller.get().footerVisible()).isFalse();
        // REQ-20261117-180000：有行视图水印模板透传（行缺省 null=仅用户名）
        assertThat(controller.get().watermarkTemplate()).isNull();
        config.setWatermarkTemplate("{username} {time}");
        assertThat(controller.get().watermarkTemplate()).isEqualTo("{username} {time}");
        assertThat(controller.update(new AdminSystemConfigUpdate(true, false, 0, null, false, null, 3L)).version())
                .isEqualTo(3L);

        when(useCase.findCurrent()).thenReturn(Optional.empty());
        AdminSystemConfigView defaults = controller.get();
        assertThat(defaults.watermarkEnabled()).isTrue();
        // REQ-20261117-011514：无行缺省视图验证码缺省关闭
        assertThat(defaults.captchaEnabled()).isFalse();
        assertThat(defaults.lockIdleTimeoutMs()).isZero();
        // REQ-20261117-025200：无行缺省视图页脚不显示（确认记录⑤）
        assertThat(defaults.footerCopyright()).isNull();
        assertThat(defaults.footerVisible()).isFalse();
        // REQ-20261117-180000：无行缺省视图水印模板 null（仅用户名）
        assertThat(defaults.watermarkTemplate()).isNull();
        assertThat(defaults.version()).isEqualTo(0L);

        // 权限强制点：PUT 标注 system:config:update；GET 认证即可读（无标注）
        var putMethod = SystemConfigController.class.getMethod("update", AdminSystemConfigUpdate.class);
        assertThat(putMethod.getAnnotation(com.acme.platform.system.security.RequirePermission.class).value())
                .isEqualTo(com.acme.platform.system.api.SystemPermissions.CONFIG_UPDATE);
        assertThat(SystemConfigController.class.getMethod("get")
                .getAnnotation(com.acme.platform.system.security.RequirePermission.class)).isNull();
    }

    @Test
    void internalSystemConfigControllerReturnsPolicyEnvelope() {
        // REQ-20261117-011514 captcha-policy 内部端点全表面（有行透传 / 无行缺省 false；Result 信封）
        SystemConfigUseCase useCase = mock(SystemConfigUseCase.class);
        when(useCase.findCaptchaPolicyByTenant("acme"))
                .thenReturn(new com.acme.platform.system.api.CaptchaPolicyView(true));
        when(useCase.findCaptchaPolicyByTenant("empty"))
                .thenReturn(new com.acme.platform.system.api.CaptchaPolicyView(false));
        InternalSystemConfigController controller = new InternalSystemConfigController(useCase);

        var enabled = controller.captchaPolicy("acme");
        assertThat(enabled.getSuccess()).isTrue();
        assertThat(enabled.getData().captchaEnabled()).isTrue();

        var disabled = controller.captchaPolicy("empty");
        assertThat(disabled.getSuccess()).isTrue();
        assertThat(disabled.getData().captchaEnabled()).isFalse();
    }

    @Test
    void should_return200ForConfigBusinessErrorsAnd403ForPermissionDenial() throws Exception {
        SystemConfigUseCase useCase = mock(SystemConfigUseCase.class);
        GlobalExceptionTranslator translator = new GlobalExceptionTranslator(
                new StaticListableBeanFactory().getBeanProvider(PromptLocalizer.class));
        var mvc = MockMvcBuilders.standaloneSetup(new SystemConfigController(useCase, translator))
                .setControllerAdvice(translator).build();

        for (ErrorCode error : List.of(SystemError.SYSTEM_CONFIG_INVALID,
                WebError.CONCURRENCY_CONFLICT, FlexError.STALE_WRITE, SystemError.PERMISSION_DENIED)) {
            org.mockito.Mockito.doThrow(new BizException(error)).when(useCase).update(any());
            var result = mvc.perform(put("/admin/v1/system/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"watermarkEnabled\":true,\"captchaEnabled\":false,"
                                    + "\"lockIdleTimeoutMs\":0,\"footerCopyright\":null,"
                                    + "\"footerVisible\":false,\"watermarkTemplate\":null,\"version\":\"0\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .isEqualTo(error == SystemError.PERMISSION_DENIED ? 403 : 200);
            var body = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(result.getResponse().getContentAsByteArray());
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("code").asText()).isEqualTo(error.code());
        }
    }

    // 菜单树组装需实体 id（TreeBuilder DP-004 非空约定）
    private static SysMenu menuWithId(long id, long parentId, String name, MenuType type) {
        SysMenu menu = new SysMenu(parentId, name, type, null, null, null, 0, true, null);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
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
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return role;
    }

    // 部门树组装需实体 id 与 path
    private static SysDept deptWithId(long id, long parentId, String name) {
        SysDept dept = new SysDept(parentId, name, 0, EnableStatus.ENABLED);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(dept, id);
            var versionField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(dept, 0L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        dept.setPath(parentId == 0 ? "/" + id + "/" : "/" + parentId + "/" + id + "/");
        return dept;
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
        // REQ-20261114-120104：数据范围设置端点
        controller.updateDataScope(1L, new com.acme.platform.system.api.dto.AdminRoleDataScope(
                "CUSTOM", List.of(11L, 12L)));
    }

    @Test
    void permissionControllerFullSurface() {
        PermissionUseCase useCase = mock(PermissionUseCase.class);
        SysPermission permission = new SysPermission("system:user:create", "创建用户",
                PermissionType.API, "POST", "/y");
        when(useCase.create(any())).thenReturn(permission);
        when(useCase.get(any())).thenReturn(permission);
        when(useCase.update(any(), any())).thenReturn(permission);
        when(useCase.page(any(), any(), any(), any())).thenReturn(new PageResult<>(List.of(permission), 1L));
        PermissionController controller = new PermissionController(useCase);

        assertThat(controller.list(1, 20, null, null, null).total()).isEqualTo(1L);
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

        assertThat(controller.tree(null, null, null, null)).hasSize(1);
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
        assertThat(controller.create(new AdminTenantCreate("acme", "Acme 租户", null, null, null, null,
                null)).tenantCode())
                .isEqualTo("acme");
        assertThat(controller.get(2L).tenantName()).isEqualTo("Acme 租户");
        assertThat(controller.update(2L, new AdminTenantUpdate("Acme 租户", null, null, null, null, 1L,
                null)).tenantCode())
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
                java.util.Set.of(1L, 2L), "研发部"));
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
        // REQ-20261117-180000：部门名透传（水印 {deptName} 供给）
        assertThat(me.deptName()).isEqualTo("研发部");
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
        com.acme.platform.system.security.DataScopeService dataScopeService =
                mock(com.acme.platform.system.security.DataScopeService.class);
        when(useCase.hasPermission(anyString())).thenReturn(true);
        when(dataScopeService.currentSubjectWithUserIds()).thenReturn(
                com.acme.framework.security.support.DataScopeDecision.of(
                        com.acme.framework.security.support.DataScope.CUSTOM,
                        java.util.List.of(11L), java.util.List.of("42")));
        AuthorizationController controller = new AuthorizationController(useCase, dataScopeService);
        assertThat(controller.hasPermission("auth:credential:manage").getData()).isTrue();
        // REQ-20261114-120104：内部数据范围端点
        var view = controller.currentDataScope().getData();
        assertThat(view.scope()).isEqualTo("CUSTOM");
        assertThat(view.deptIds()).containsExactly(11L);
        assertThat(view.userIds()).containsExactly("42");
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
        // REQ-20261114-120104：部门树（嵌套 children + 禁用态透传）/ 平铺 / 岗位视图
        SysDept root = deptWithId(1L, 0L, "总部");
        SysDept child = deptWithId(2L, 1L, "研发部");
        child.setStatus(EnableStatus.DISABLED);
        var deptTree = EntityViews.deptTree(List.of(root, child));
        assertThat(deptTree).hasSize(1);
        assertThat(deptTree.get(0).children()).extracting(AdminDeptView::deptName).containsExactly("研发部");
        assertThat(deptTree.get(0).children().get(0).status()).isEqualTo(EnableStatus.DISABLED);
        assertThat(EntityViews.deptView(root).children()).isEmpty();
        SysPosition position = new SysPosition("engineer", "工程师", EnableStatus.ENABLED);
        assertThat(EntityViews.positionView(position).positionCode()).isEqualTo("engineer");
        // REQ-20261117-011514：系统配置视图 captchaEnabled 透传 + 缺省视图缺省关闭
        SysConfig config = new SysConfig(false, true, 60_000);
        assertThat(EntityViews.systemConfigView(config).captchaEnabled()).isTrue();
        assertThat(EntityViews.systemConfigDefaultView().captchaEnabled()).isFalse();
        assertThat(EntityViews.systemConfigDefaultView().watermarkEnabled()).isTrue();
        // REQ-20261117-025200：页脚两字段透传 + 缺省视图不显示（确认记录⑤）
        SysConfig withFooter = new SysConfig(false, true, 60_000, "© ACME", true);
        assertThat(EntityViews.systemConfigView(withFooter).footerCopyright()).isEqualTo("© ACME");
        assertThat(EntityViews.systemConfigView(withFooter).footerVisible()).isTrue();
        assertThat(EntityViews.systemConfigDefaultView().footerCopyright()).isNull();
        assertThat(EntityViews.systemConfigDefaultView().footerVisible()).isFalse();
        // REQ-20261117-180000：水印模板透传 + 缺省视图 null（仅用户名）
        SysConfig withTemplate = new SysConfig(false, true, 60_000, null, false, "{username} {date}");
        assertThat(EntityViews.systemConfigView(withTemplate).watermarkTemplate()).isEqualTo("{username} {date}");
        assertThat(EntityViews.systemConfigView(config).watermarkTemplate()).isNull();
        assertThat(EntityViews.systemConfigDefaultView().watermarkTemplate()).isNull();
        // REQ-20261117-025200：租户视图联系人 / 有效期透传
        SysTenant withProfile = new SysTenant("acme", "Acme", null);
        withProfile.setContactName("张三");
        withProfile.setContactPhone("13800000000");
        withProfile.setExpiresAt(java.time.Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(EntityViews.tenantView(withProfile).contactName()).isEqualTo("张三");
        assertThat(EntityViews.tenantView(withProfile).contactPhone()).isEqualTo("13800000000");
        assertThat(EntityViews.tenantView(withProfile).expiresAt())
                .isEqualTo(java.time.Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void internalTenantControllerReturnsByCodeView() {
        // REQ-20261117-025200 by-code 内部端点全表面（存在 → 视图透传；Result 信封）
        TenantUseCase useCase = mock(TenantUseCase.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        tenant.setExpiresAt(java.time.Instant.parse("2027-01-01T00:00:00Z"));
        when(useCase.findByCode("acme")).thenReturn(tenant);
        InternalTenantController controller = new InternalTenantController(useCase);

        var result = controller.byCode("acme");
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData().tenantCode()).isEqualTo("acme");
        assertThat(result.getData().tenantName()).isEqualTo("Acme 租户");
        assertThat(result.getData().status()).isEqualTo("ENABLED");
        assertThat(result.getData().expiresAt()).isEqualTo(java.time.Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void internalSystemConfigControllerReturnsPublicView() {
        // REQ-20261117-025200 public-view 内部端点全表面（有行按行值 / 无行缺省 null·false；Result 信封）
        SystemConfigUseCase useCase = mock(SystemConfigUseCase.class);
        when(useCase.findPublicViewByTenant("acme"))
                .thenReturn(new com.acme.platform.system.api.TenantPublicInternalView("© ACME", true));
        when(useCase.findPublicViewByTenant("empty"))
                .thenReturn(new com.acme.platform.system.api.TenantPublicInternalView(null, false));
        InternalSystemConfigController controller = new InternalSystemConfigController(useCase);

        var configured = controller.publicView("acme");
        assertThat(configured.getSuccess()).isTrue();
        assertThat(configured.getData().footerCopyright()).isEqualTo("© ACME");
        assertThat(configured.getData().footerVisible()).isTrue();

        var empty = controller.publicView("empty");
        assertThat(empty.getSuccess()).isTrue();
        assertThat(empty.getData().footerCopyright()).isNull();
        assertThat(empty.getData().footerVisible()).isFalse();
    }

    @Test
    void internalAuthorizationControllerReturnsBuiltinRoles() {
        // REQ-20261117-025200 内置角色实查端点全表面（确认记录④：roles 仅查询键，内置角色行权威）
        AuthorizationUseCase useCase = mock(AuthorizationUseCase.class);
        com.acme.platform.system.security.DataScopeService dataScopeService =
                mock(com.acme.platform.system.security.DataScopeService.class);
        when(useCase.currentBuiltinRoles()).thenReturn(java.util.Set.of("super_admin"));
        when(dataScopeService.currentSubjectWithUserIds()).thenReturn(
                com.acme.framework.security.support.DataScopeDecision.of(
                        com.acme.framework.security.support.DataScope.CUSTOM,
                        java.util.List.of(11L), java.util.List.of("42")));
        AuthorizationController controller = new AuthorizationController(useCase, dataScopeService);

        var result = controller.currentBuiltinRoles();
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("super_admin");
    }

    @Test
    void pageQueryDefaultsFromRequestParams() {
        // 分页基元缺省口径（common-contract PageQuery）
        PageQuery query = new PageQuery(null, null);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
    }
}
