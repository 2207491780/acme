package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminTenantInitRequest;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户开通初始化用例测试（tenant-management-complete）：编排完整性、平台保留域排除
 * （权限/菜单/空目录剔除）、目标租户上下文绑定与恢复、唯一约束兜底。
 */
class TenantInitUseCaseTest {

    private final SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
    private final SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
    private final SysMenuRepository menuRepository = mock(SysMenuRepository.class);
    private final SysRoleMenuRepository roleMenuRepository = mock(SysRoleMenuRepository.class);
    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);

    private final TenantInitUseCase useCase = new TenantInitUseCase(tenantRepository, roleRepository,
            permissionRepository, rolePermissionRepository, menuRepository, roleMenuRepository,
            userRepository, userRoleRepository);

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void initializesTenantRoleAdminAndRestoresContext() {
        TenantContext.bind(new TenantContext("default"));
        when(tenantRepository.existsByTenantCode("acme")).thenReturn(false);
        when(tenantRepository.save(any(SysTenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.save(any(SysRole.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // 权限点：2 可授 + 2 平台保留（system:tenant / infra:audit）
        when(permissionRepository.findAll()).thenReturn(List.of(
                permission(101L, "system:user:list"), permission(102L, "system:role:list"),
                permission(201L, "system:tenant:list"), permission(301L, "infra:audit:list")));
        // 菜单：系统管理目录(1) + 用户(2)/租户(7)/平台运营目录(8, 空目录)/审计(9)
        when(menuRepository.findAll()).thenReturn(List.of(
                menu(1L, 0L, null), menu(2L, 1L, "system:user:list"), menu(7L, 1L, "system:tenant:list"),
                menu(8L, 0L, null), menu(9L, 8L, "infra:audit:list")));

        var result = useCase.initialize(
                new AdminTenantInitRequest("acme", "Acme 租户", null, "acme_admin", "租户管理员"));

        assertThat(result.tenantCode()).isEqualTo("acme");
        assertThat(result.adminUsername()).isEqualTo("acme_admin");

        // 角色：租户管理员，权限排除保留域（仅 101/102）
        ArgumentCaptor<List<com.acme.platform.system.domain.SysRolePermission>> permCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(permCaptor.capture());
        assertThat(permCaptor.getValue())
                .extracting(com.acme.platform.system.domain.SysRolePermission::getPermissionId)
                .containsExactlyInAnyOrder(101L, 102L);

        // 菜单：保留菜单及其祖先目录保留，保留域菜单(7/9)与空目录(8)剔除
        ArgumentCaptor<List<com.acme.platform.system.domain.SysRoleMenu>> menuCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(roleMenuRepository).saveAll(menuCaptor.capture());
        assertThat(menuCaptor.getValue())
                .extracting(com.acme.platform.system.domain.SysRoleMenu::getMenuId)
                .containsExactlyInAnyOrder(1L, 2L);

        // 管理员 + 角色绑定 + 上下文恢复
        verify(userRoleRepository).save(any());
        assertThat(TenantContext.get().tenantId()).isEqualTo("default");
    }

    @Test
    void duplicatedTenantCodeRejected() {
        TenantContext.bind(new TenantContext("default"));
        when(tenantRepository.existsByTenantCode("acme")).thenReturn(true);
        assertThatThrownBy(() -> useCase.initialize(
                new AdminTenantInitRequest("acme", "Acme 租户", null, "acme_admin", "租户管理员")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.TENANT_CODE_DUPLICATED.code()));
        assertThat(TenantContext.get().tenantId()).isEqualTo("default");
    }

    @Test
    void usernameUniqueConstraintBackstopMapped() {
        TenantContext.bind(new TenantContext("default"));
        when(tenantRepository.existsByTenantCode("acme")).thenReturn(false);
        when(tenantRepository.save(any(SysTenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.save(any(SysRole.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(permissionRepository.findAll()).thenReturn(List.of());
        when(menuRepository.findAll()).thenReturn(List.of());
        when(userRepository.save(any(SysUser.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "uk_sys_user_username"));

        assertThatThrownBy(() -> useCase.initialize(
                new AdminTenantInitRequest("acme", "Acme 租户", null, "admin", "重复管理员")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USERNAME_DUPLICATED.code()));
        // 失败路径同样恢复上下文
        assertThat(TenantContext.get().tenantId()).isEqualTo("default");
    }

    private static SysPermission permission(Long id, String code) {
        SysPermission permission = new SysPermission(code, code, com.acme.platform.system.api.PermissionType.API,
                "GET", "/x");
        setId(permission, id);
        return permission;
    }

    private static SysMenu menu(Long id, Long parentId, String permissionCode) {
        SysMenu menu = new SysMenu(parentId, "菜单" + id, MenuType.MENU, null, null, null, 0, true,
                permissionCode);
        setId(menu, id);
        return menu;
    }

    /** 种子实体 id 反射注入（AuditBase id 无 setter，测试同 RoleAndTenantUseCaseTest 先例）。 */
    private static void setId(Object entity, Long id) {
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
