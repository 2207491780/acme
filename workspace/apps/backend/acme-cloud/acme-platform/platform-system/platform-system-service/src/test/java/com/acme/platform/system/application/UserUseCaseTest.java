package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysPositionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import com.acme.platform.system.security.DataScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理用例测试（FR-006/007：唯一性、内置保护、本人删除拒绝、绑定替换幂等；
 * REQ-20261114-120104：部门/岗位归属校验）。
 */
class UserUseCaseTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);
    private final SysDeptRepository deptRepository = mock(SysDeptRepository.class);
    private final SysPositionRepository positionRepository = mock(SysPositionRepository.class);
    private final DataScopeService dataScopeService = mock(DataScopeService.class);

    private final UserUseCase useCase = new UserUseCase(userRepository, roleRepository, userRoleRepository,
            deptRepository, positionRepository, dataScopeService);

    /** REQ-20261125-001738：查重签名收敛为 (tenantId, username)，显式绑定租户域以钉死域内语义。 */
    @BeforeEach
    void bindTenant() {
        TenantContext.bind(new TenantContext("default"));
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void createDuplicateUsernameRejected409() {
        when(userRepository.existsByUsername("default", "admin")).thenReturn(true);
        assertThatThrownBy(() -> useCase.create(new AdminUserCreate("admin", "昵称", null, null, List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USERNAME_DUPLICATED.code()));
        // REQ-20261125-001738：查重按 (tenantId, username) 两参数域内进行
        verify(userRepository).existsByUsername("default", "admin");
    }

    @Test
    void createSavesUserAndBindsRoles() {
        when(userRepository.existsByUsername("default", "u1")).thenReturn(false);
        when(userRepository.save(any(SysUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 绑定角色存在性校验打桩（5L 角色存在）
        com.acme.platform.system.domain.SysRole role =
                new com.acme.platform.system.domain.SysRole("ops", "运维", null, false);
        when(roleRepository.findAllById(java.util.Set.of(5L))).thenReturn(List.of(role));
        useCase.create(new AdminUserCreate("u1", "用户一", null, null, List.of(5L)));
        verify(userRepository).save(any(SysUser.class));
        // 绑定行批量写入（saveAll），内容为 5L 角色
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.acme.platform.system.domain.SysUserRole>> captor =
                (org.mockito.ArgumentCaptor<java.util.List<com.acme.platform.system.domain.SysUserRole>>)
                        (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(com.acme.platform.system.domain.SysUserRole::getRoleId)
                .containsExactly(5L);
    }

    @Test
    void deleteBuiltInAdminRejected() {
        SysUser builtIn = user(1L, "admin", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(builtIn));
        assertThatThrownBy(() -> useCase.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USER_DELETE_DENIED.code()));
    }

    @Test
    void deleteOperatorSelfRejected() {
        UserContext.bind(new UserContext("42", java.util.Set.of()));
        SysUser target = user(42L, "self", false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> useCase.delete(42L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USER_DELETE_DENIED.code()));
    }

    @Test
    void deleteUnbindsRolesThenRemoves() {
        SysUser target = user(7L, "u7", false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        useCase.delete(7L);
        verify(userRoleRepository).deleteByUserId(7L);
        verify(userRepository).delete(target);
    }

    @Test
    void deleteMissingIsIdempotentSuccess() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatCode(() -> useCase.delete(99L)).doesNotThrowAnyException();
        verify(userRepository, never()).delete(any(SysUser.class));
    }

    @Test
    void updateVersionMismatchRejected409() {
        SysUser existing = user(7L, "u7", false);
        setVersion(existing, 3L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> useCase.update(7L, new AdminUserUpdate("新昵称", null, null, 2L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("SYS-01-0003"));
    }

    @Test
    void bindRolesValidatesExistence() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "u7", false)));
        when(roleRepository.findAllById(java.util.Set.of(5L))).thenReturn(List.of());
        assertThatThrownBy(() -> useCase.bindRoles(7L, List.of(5L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.ROLE_NOT_FOUND.code()));
    }

    @Test
    void createWithMissingDeptOrPositionRejected() {
        // REQ-20261114-120104：归属校验（跨租户 id 经租户过滤天然不存在）
        when(userRepository.existsByUsername("default", "u1")).thenReturn(false);
        when(deptRepository.findById(11L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> useCase.create(new AdminUserCreate("u1", "用户", null, null,
                List.of(), 11L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_NOT_FOUND.code()));
        when(positionRepository.findById(21L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> useCase.create(new AdminUserCreate("u1", "用户", null, null,
                List.of(), null, 21L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.POSITION_NOT_FOUND.code()));
    }

    @Test
    void createWithOrgRefsSavesAssignment() {
        // REQ-20261114-120104：合法归属字段随实体落库
        when(userRepository.existsByUsername("default", "u1")).thenReturn(false);
        when(deptRepository.findById(11L)).thenReturn(Optional.of(new SysDept(0L, "总部", 0, null)));
        when(positionRepository.findById(21L)).thenReturn(java.util.Optional.of(
                new com.acme.platform.system.domain.SysPosition("eng", "工程师", null)));
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SysUser saved = useCase.create(new AdminUserCreate("u1", "用户", null, null, List.of(), 11L, 21L));
        assertThat(saved.getDeptId()).isEqualTo(11L);
        assertThat(saved.getPositionId()).isEqualTo(21L);
    }

    @Test
    void statusUpdateChangesStatus() {
        SysUser existing = user(7L, "u7", false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        useCase.updateStatus(7L, EnableStatus.DISABLED);
        assertThat(existing.getStatus()).isEqualTo(EnableStatus.DISABLED);
    }

    private static SysUser user(Long id, String username, boolean builtIn) {
        SysUser user = new SysUser(username, "昵称", null, null, builtIn);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return user;
    }

    private static void setVersion(SysUser user, Long version) {
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("version");
            field.setAccessible(true);
            field.set(user, version);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
