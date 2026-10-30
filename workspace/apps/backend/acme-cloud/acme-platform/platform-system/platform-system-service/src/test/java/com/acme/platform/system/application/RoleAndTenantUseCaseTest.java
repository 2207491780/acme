package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleUpdate;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色 / 菜单绑定 / 租户用例测试（FR-006/007/008：引用约束、父链补全、默认租户保护；
 * 角色父子树：父存在性、防环、内置角色锁父、有子拒删）。
 */
class RoleAndTenantUseCaseTest {

    @Test
    void bindMenusCompletesAncestorChain() {
        SysMenuRepository menuRepository = mock(SysMenuRepository.class);
        SysRoleMenuRepository roleMenuRepository = mock(SysRoleMenuRepository.class);
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), roleMenuRepository,
                mock(SysPermissionRepository.class), menuRepository);

        SysRole role = new SysRole("ops", "运维", null, false);
        setId(role, 1L);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));

        // 闭包按 ID 逐层收集：存在性校验与父链各层均走 findAllById（不再全表加载）
        SysMenu root = menu(1L, 0L, "系统管理", MenuType.DIRECTORY);
        SysMenu child = menu(2L, 1L, "用户管理", MenuType.MENU);
        SysMenu leaf = menu(3L, 2L, "用户列表", MenuType.MENU);
        when(menuRepository.findAllById(java.util.Set.of(3L))).thenReturn(List.of(leaf));
        when(menuRepository.findAllById(java.util.Set.of(2L))).thenReturn(List.of(child));
        when(menuRepository.findAllById(java.util.Set.of(1L))).thenReturn(List.of(root));

        useCase.bindMenus(1L, List.of(3L));

        // 断言补全父链后批量写入绑定行 = 3（root + child + leaf）
        org.mockito.ArgumentCaptor<List<SysRoleMenu>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(roleMenuRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(SysRoleMenu::getMenuId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void createRoleDefaultsToRootAndKeepsParentId() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        when(roleRepository.existsByRoleCode(any())).thenReturn(false);
        when(roleRepository.findById(9L)).thenReturn(Optional.of(role(9L, 0L, "parent", false)));
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(useCase.create(new AdminRoleCreate(null, "root", "根角色", null)).getParentId())
                .isEqualTo(SysRole.ROOT_PARENT_ID);
        assertThat(useCase.create(new AdminRoleCreate(9L, "ops", "运维", null)).getParentId())
                .isEqualTo(9L);
    }

    @Test
    void createRoleUnderMissingParentRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        when(roleRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.create(new AdminRoleCreate(9L, "ops", "运维", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_NOT_FOUND.code()));
    }

    @Test
    void reparentUnderSelfOrDescendantRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        SysRole parent = role(1L, 0L, "a", false);
        SysRole child = role(2L, 1L, "b", false);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(child));

        // 自环：挂到自身
        assertThatThrownBy(() -> useCase.update(1L, new AdminRoleUpdate("A", null, 1L, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_PARENT_CYCLE.code()));
        // 子孙环：挂到子角色之下（沿父链 2→1 命中自身）
        assertThatThrownBy(() -> useCase.update(1L, new AdminRoleUpdate("A", null, 2L, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_PARENT_CYCLE.code()));
        // 拒绝后父节点不变
        assertThat(parent.getParentId()).isEqualTo(SysRole.ROOT_PARENT_ID);
    }

    @Test
    void reparentMovesUnderParentAndBackToRoot() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        SysRole role = role(1L, 0L, "a", false);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role(2L, 0L, "b", false)));
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(useCase.update(1L, new AdminRoleUpdate("A", null, 2L, 0L)).getParentId()).isEqualTo(2L);
        assertThat(useCase.update(1L, new AdminRoleUpdate("A", null, 0L, 0L)).getParentId())
                .isEqualTo(SysRole.ROOT_PARENT_ID);
    }

    @Test
    void builtInRoleReparentRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, 0L, "super_admin", true)));
        assertThatThrownBy(() -> useCase.update(1L, new AdminRoleUpdate("超管", null, 2L, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_REPARENT_DENIED.code()));
    }

    @Test
    void deleteRoleWithChildrenRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        SysRole role = role(1L, 0L, "ops", false);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.existsByParentId(1L)).thenReturn(true);
        assertThatThrownBy(() -> useCase.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_DELETE_DENIED.code()));
        verify(roleRepository, never()).delete(role);
    }

    @Test
    void defaultTenantDisableRejected() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        TenantUseCase useCase = new TenantUseCase(tenantRepository);
        SysTenant defaultTenant = new SysTenant("default", "默认租户", null);
        setId(defaultTenant, 1L);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(defaultTenant));
        assertThatThrownBy(() -> useCase.updateStatus(1L, EnableStatus.DISABLED))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.TENANT_DISABLE_DENIED.code()));
    }

    @Test
    void tenantCodeDuplicatedRejected() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        when(tenantRepository.existsByTenantCode("default")).thenReturn(true);
        TenantUseCase useCase = new TenantUseCase(tenantRepository);
        assertThatThrownBy(() -> useCase.create(new AdminTenantCreate("default", "重复租户", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.TENANT_CODE_DUPLICATED.code()));
    }

    private static RoleUseCase roleUseCase(SysRoleRepository roleRepository) {
        return new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), mock(SysRoleMenuRepository.class),
                mock(SysPermissionRepository.class), mock(SysMenuRepository.class));
    }

    private static SysRole role(Long id, Long parentId, String code, boolean builtIn) {
        SysRole role = new SysRole(parentId, code, code, null, builtIn);
        setAuditField(role, "id", id);
        setAuditField(role, "version", 0L);
        return role;
    }

    private static SysMenu menu(Long id, Long parentId, String name, MenuType type) {
        SysMenu menu = new SysMenu(parentId, name, type, null, null, null, 0, true, null);
        setId(menu, id);
        return menu;
    }

    private static void setId(Object entity, Long id) {
        setAuditField(entity, "id", id);
    }

    private static void setAuditField(Object entity, String field, Object value) {
        try {
            var declared = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(entity, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
