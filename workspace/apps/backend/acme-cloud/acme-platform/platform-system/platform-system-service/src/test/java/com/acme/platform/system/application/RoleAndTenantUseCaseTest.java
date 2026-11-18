package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.security.support.DataScope;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleDataScope;
import com.acme.platform.system.api.dto.AdminRoleUpdate;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleDept;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.client.TenantSessionRevocationGateway;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
                mock(SysPermissionRepository.class), menuRepository,
                mock(SysDeptRepository.class), mock(SysRoleDeptRepository.class));

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
    void createRoleWithReservedCodeRejected() {
        // review 修复 P0 回归：保留编码任何租户不得自建（防伪造 claims 提权）
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        for (String reserved : RoleUseCase.RESERVED_ROLE_CODES) {
            assertThatThrownBy(() -> useCase.create(new AdminRoleCreate(null, reserved, "伪超管", null)))
                    .as("保留编码 %s 应被拒绝", reserved)
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo(SystemError.ROLE_CODE_RESERVED.code()));
        }
        verify(roleRepository, never()).save(any());
    }

    @Test
    void bindPermissionsReservedDomainRejectedForNonBuiltIn() {
        // review 修复 P2 回归：保留域（system:tenant:* / infra:audit:*）不得经授权面绕过
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
        SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                rolePermissionRepository, mock(SysRoleMenuRepository.class),
                permissionRepository, mock(SysMenuRepository.class),
                mock(SysDeptRepository.class), mock(SysRoleDeptRepository.class));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role(2L, 0L, "ops", false)));
        when(permissionRepository.findAllById(java.util.Set.of(32L)))
                .thenReturn(List.of(permission(32L, "system:tenant:create")));

        assertThatThrownBy(() -> useCase.bindPermissions(2L, List.of(32L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_GRANT_RESERVED.code()));
        verify(rolePermissionRepository, never()).deleteByRoleId(any());
    }

    @Test
    void bindPermissionsReservedDomainAllowedForBuiltIn() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
        SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                rolePermissionRepository, mock(SysRoleMenuRepository.class),
                permissionRepository, mock(SysMenuRepository.class),
                mock(SysDeptRepository.class), mock(SysRoleDeptRepository.class));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, 0L, "super_admin", true)));
        when(permissionRepository.findAllById(java.util.Set.of(32L)))
                .thenReturn(List.of(permission(32L, "system:tenant:create")));

        useCase.bindPermissions(1L, List.of(32L));
        verify(rolePermissionRepository).deleteByRoleId(1L);
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

    // ---------- 数据范围五档设置（REQ-20261114-120104，AC-3） ----------

    @Test
    void updateDataScopeUnknownTierRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        RoleUseCase useCase = roleUseCase(roleRepository);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role(2L, 0L, "ops", false)));
        assertThatThrownBy(() -> useCase.updateDataScope(2L, new AdminRoleDataScope("TENANT", List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DATA_SCOPE_INVALID.code()));
    }

    @Test
    void updateDataScopeCustomWithoutDeptIdsRejected() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        SysRoleDeptRepository roleDeptRepository = mock(SysRoleDeptRepository.class);
        SysDeptRepository deptRepository = mock(SysDeptRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), mock(SysRoleMenuRepository.class),
                mock(SysPermissionRepository.class), mock(SysMenuRepository.class),
                deptRepository, roleDeptRepository);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role(2L, 0L, "ops", false)));
        // CUSTOM 未传部门集 → DATA_SCOPE_INVALID
        assertThatThrownBy(() -> useCase.updateDataScope(2L, new AdminRoleDataScope("CUSTOM", List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DATA_SCOPE_INVALID.code()));
        // CUSTOM 含未知 / 跨租户部门（租户过滤后不存在）→ DATA_SCOPE_INVALID
        assertThatThrownBy(() -> useCase.updateDataScope(2L, new AdminRoleDataScope("CUSTOM", List.of(11L))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DATA_SCOPE_INVALID.code()));
        verify(roleDeptRepository, never()).deleteByRoleId(any());
    }

    @Test
    void updateDataScopeCustomWritesBindingRowsAndTier() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        SysRoleDeptRepository roleDeptRepository = mock(SysRoleDeptRepository.class);
        SysDeptRepository deptRepository = mock(SysDeptRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), mock(SysRoleMenuRepository.class),
                mock(SysPermissionRepository.class), mock(SysMenuRepository.class),
                deptRepository, roleDeptRepository);
        SysRole role = role(2L, 0L, "ops", false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role));
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deptRepository.findAllById(java.util.Set.of(11L, 12L))).thenReturn(List.of(
                new SysDept(0L, "总部", 0, null), new SysDept(0L, "研发部", 1, null)));

        useCase.updateDataScope(2L, new AdminRoleDataScope("CUSTOM", List.of(11L, 12L)));

        // 绑定行整体重写：先物理解绑再批量写入（7.2.7）
        verify(roleDeptRepository).deleteByRoleId(2L);
        org.mockito.ArgumentCaptor<List<SysRoleDept>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(roleDeptRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(SysRoleDept::getDeptId)
                .containsExactlyInAnyOrder(11L, 12L);
        assertThat(role.getDataScope()).isEqualTo(DataScope.CUSTOM.name());
    }

    @Test
    void updateDataScopeNonCustomClearsBindingRows() {
        SysRoleRepository roleRepository = mock(SysRoleRepository.class);
        SysRoleDeptRepository roleDeptRepository = mock(SysRoleDeptRepository.class);
        SysDeptRepository deptRepository = mock(SysDeptRepository.class);
        RoleUseCase useCase = new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), mock(SysRoleMenuRepository.class),
                mock(SysPermissionRepository.class), mock(SysMenuRepository.class),
                deptRepository, roleDeptRepository);
        SysRole role = role(2L, 0L, "ops", false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role));
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.updateDataScope(2L, new AdminRoleDataScope("DEPT_AND_CHILD", List.of(11L)));

        // 非 CUSTOM 档清空绑定行（防档位回落遗留脏集）；dept 入参不落绑定
        verify(roleDeptRepository).deleteByRoleId(2L);
        verify(roleDeptRepository, never()).saveAll(any());
        assertThat(role.getDataScope()).isEqualTo(DataScope.DEPT_AND_CHILD.name());
    }

    @Test
    void defaultTenantDisableRejected() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());
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
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());
        assertThatThrownBy(() -> useCase.create(new AdminTenantCreate("default", "重复租户", null,
                null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.TENANT_CODE_DUPLICATED.code()));
    }

    // ---------- REQ-20261117-025200：租户档案字段透传（AC-4） ----------

    @Test
    void should_persistContactAndExpiry_when_createTenantCarriesProfileFields() {
        // AC-4：创建携带联系人 / 有效期 → 实体三新字段回显一致（可空语义正确）
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        when(tenantRepository.existsByTenantCode(any())).thenReturn(false);
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());

        Instant expiresAt = Instant.parse("2027-01-01T00:00:00Z");
        SysTenant created = useCase.create(new AdminTenantCreate("acme", "Acme 租户", "备注",
                "张三", "13800000000", expiresAt));

        assertThat(created.getContactName()).isEqualTo("张三");
        assertThat(created.getContactPhone()).isEqualTo("13800000000");
        assertThat(created.getExpiresAt()).isEqualTo(expiresAt);
        // 可空语义：缺省字段为 null（长期有效）
        SysTenant longTerm = useCase.create(new AdminTenantCreate("lt", "长期租户", null, null, null, null));
        assertThat(longTerm.getContactName()).isNull();
        assertThat(longTerm.getContactPhone()).isNull();
        assertThat(longTerm.getExpiresAt()).isNull();
    }

    @Test
    void should_applyContactAndExpiry_when_updateTenantCarriesProfileFields() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        setId(tenant, 2L);
        setAuditField(tenant, "version", 0L);
        when(tenantRepository.findById(2L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());

        Instant expiresAt = Instant.parse("2027-06-01T00:00:00Z");
        SysTenant updated = useCase.update(2L, new AdminTenantUpdate("Acme 租户二", null,
                "李四", "13900000000", expiresAt, 0L));

        assertThat(updated.getContactName()).isEqualTo("李四");
        assertThat(updated.getContactPhone()).isEqualTo("13900000000");
        assertThat(updated.getExpiresAt()).isEqualTo(expiresAt);
    }

    // ---------- REQ-20261117-025200：by-code 目录查询（确认记录③④） ----------

    @Test
    void should_returnTenant_when_findByCodeHits() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        when(tenantRepository.findByTenantCode("acme")).thenReturn(Optional.of(tenant));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());

        assertThat(useCase.findByCode("acme")).isSameAs(tenant);
    }

    @Test
    void should_throwTenantNotFound_when_findByCodeMissing() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        when(tenantRepository.findByTenantCode("none")).thenReturn(Optional.empty());
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());

        assertThatThrownBy(() -> useCase.findByCode("none"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.TENANT_NOT_FOUND.code()));
    }

    // ---------- REQ-20261117-025200：登录下拉排除已过期租户（AC-5 前半，确认记录③） ----------

    @Test
    void should_excludeExpiredTenants_when_listEnabled() {
        // 下拉不含过期租户：expiresAt 非空且 <= now 的启用租户被过滤，长期 / 未来有效期租户保留
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        SysTenant active = new SysTenant("active", "启用租户", null);
        SysTenant future = new SysTenant("future", "未来过期租户", null);
        future.setExpiresAt(Instant.now().plusSeconds(3600));
        SysTenant expired = new SysTenant("expired", "已过期租户", null);
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        when(tenantRepository.findAll(any(QueryWrapper.class))).thenReturn(new ArrayList<>(List.of(
                active, future, expired)));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, tenantRevocationGateway());

        assertThat(useCase.listEnabled()).extracting(SysTenant::getTenantCode)
                .containsExactly("active", "future");
    }

    // ---------- REQ-20261117-025200：停用租户事务提交后撤销（确认记录①②，AC-1/AC-2） ----------

    @Test
    void should_revokeSessionsAfterCommit_when_disableTenant() {
        // AC-1：停用 T → 事务提交后（afterCommit）经撤销网关调用（枚举撤销），事务内只改库不外呼
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        TenantSessionRevocationGateway gateway = mock(TenantSessionRevocationGateway.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        setId(tenant, 2L);
        when(tenantRepository.findById(2L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.revokeByTenant("acme")).thenReturn(
                new TenantSessionRevocationGateway.RevocationOutcome(2, 0));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, gateway);

        TransactionSynchronizationManager.initSynchronization();
        try {
            useCase.updateStatus(2L, EnableStatus.DISABLED);
            // 事务未提交：不外呼，撤销挂在 afterCommit 同步器上
            verify(gateway, never()).revokeByTenant(anyString());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            assertThat(tenant.getStatus()).isEqualTo(EnableStatus.DISABLED);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(gateway).revokeByTenant("acme");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void should_notTriggerRevocation_when_enableTenant() {
        // 启用（ENABLED）不触发撤销（确认记录①：仅停用枚举撤销）
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        TenantSessionRevocationGateway gateway = mock(TenantSessionRevocationGateway.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        tenant.setStatus(EnableStatus.DISABLED);
        setId(tenant, 2L);
        when(tenantRepository.findById(2L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, gateway);

        useCase.updateStatus(2L, EnableStatus.ENABLED);

        verify(gateway, never()).revokeByTenant(anyString());
    }

    @Test
    void should_disableTenantStill_when_revocationCallFailsAfterCommit() {
        // AC-2：auth 撤销端点不可达 → 停用成功保留、撤销调用失败降级 WARN 不抛（接口不报错）
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        TenantSessionRevocationGateway gateway = mock(TenantSessionRevocationGateway.class);
        SysTenant tenant = new SysTenant("acme", "Acme 租户", null);
        setId(tenant, 2L);
        when(tenantRepository.findById(2L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.revokeByTenant("acme")).thenThrow(new IllegalStateException("auth unreachable"));
        TenantUseCase useCase = new TenantUseCase(tenantRepository, gateway);

        TransactionSynchronizationManager.initSynchronization();
        try {
            useCase.updateStatus(2L, EnableStatus.DISABLED);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            // 降级口径：afterCommit 内异常被吞（不回滚停用，DB 为事实源）
            assertThat(tenant.getStatus()).isEqualTo(EnableStatus.DISABLED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static TenantSessionRevocationGateway tenantRevocationGateway() {
        return mock(TenantSessionRevocationGateway.class);
    }

    private static RoleUseCase roleUseCase(SysRoleRepository roleRepository) {
        return new RoleUseCase(roleRepository, mock(SysUserRoleRepository.class),
                mock(SysRolePermissionRepository.class), mock(SysRoleMenuRepository.class),
                mock(SysPermissionRepository.class), mock(SysMenuRepository.class),
                mock(SysDeptRepository.class), mock(SysRoleDeptRepository.class));
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

    private static SysPermission permission(Long id, String code) {
        SysPermission permission = new SysPermission(code, code,
                com.acme.platform.system.api.PermissionType.API, "POST", "/x");
        setId(permission, id);
        return permission;
    }

    private static void setId(Object entity, Long id) {
        setAuditField(entity, "id", id);
    }

    private static void setAuditField(Object entity, String field, Object value) {
        try {
            var declared = com.acme.framework.flex.support.AuditBase.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(entity, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
