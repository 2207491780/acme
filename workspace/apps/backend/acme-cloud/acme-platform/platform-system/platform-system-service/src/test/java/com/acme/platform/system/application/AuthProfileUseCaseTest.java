package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录画像用例测试（tenant-management-complete 停用级联）：
 * 用户停用 / 所属租户停用均归一 DISABLED（登录链统一拒绝，auth 侧零改动映射 ACCOUNT_DISABLED）；
 * 租户目录缺失按用户状态；角色集随画像返回。
 */
class AuthProfileUseCaseTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysTenantRepository tenantRepository = mock(SysTenantRepository.class);

    private final AuthProfileUseCase useCase = new AuthProfileUseCase(
            userRepository, userRoleRepository, roleRepository, tenantRepository);

    @Test
    void enabledUserOfEnabledTenantReturnsEnabledProfileWithRoles() {
        user("u1", "acme", EnableStatus.ENABLED);
        tenant("acme", EnableStatus.ENABLED);
        SysRole role = new SysRole("ops", "运维", null, false);
        setId(role, 5L);
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(binding(5L)));
        when(roleRepository.findAllById(List.of(5L))).thenReturn(List.of(role));

        AuthProfileView profile = useCase.findByUsername("u1");
        assertThat(profile.status()).isEqualTo("ENABLED");
        assertThat(profile.tenantId()).isEqualTo("acme");
        assertThat(profile.roles()).containsExactly("ops");
    }

    @Test
    void disabledUserYieldsDisabledProfile() {
        user("u1", "acme", EnableStatus.DISABLED);
        tenant("acme", EnableStatus.ENABLED);
        assertThat(useCase.findByUsername("u1").status()).isEqualTo("DISABLED");
    }

    /** 停用级联：用户启用但所属租户停用 → 画像 DISABLED（tenant-management-complete）。 */
    @Test
    void disabledTenantCascadesToDisabledProfile() {
        user("u1", "acme", EnableStatus.ENABLED);
        tenant("acme", EnableStatus.DISABLED);
        assertThat(useCase.findByUsername("u1").status()).isEqualTo("DISABLED");
    }

    @Test
    void missingTenantRecordFallsBackToUserStatus() {
        user("u1", "ghost", EnableStatus.ENABLED);
        when(tenantRepository.findByTenantCode("ghost")).thenReturn(Optional.empty());
        assertThat(useCase.findByUsername("u1").status()).isEqualTo("ENABLED");
    }

    @Test
    void unknownUserRejected404() {
        when(userRepository.findByUsername("nobody")).thenReturn(null);
        assertThatThrownBy(() -> useCase.findByUsername("nobody"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USER_NOT_FOUND.code()));
    }

    private void user(String username, String tenantCode, EnableStatus status) {
        SysUser user = new SysUser(username, "昵称", null, null, false);
        setId(user, 1L);
        setTenantId(user, tenantCode);
        user.setStatus(status);
        when(userRepository.findByUsername(username)).thenReturn(user);
        tenant(tenantCode, EnableStatus.ENABLED);
    }

    private void tenant(String tenantCode, EnableStatus status) {
        SysTenant tenant = new SysTenant(tenantCode, tenantCode + " 租户", null);
        tenant.setStatus(status);
        when(tenantRepository.findByTenantCode(tenantCode)).thenReturn(Optional.of(tenant));
    }

    private static SysUserRole binding(Long roleId) {
        return new SysUserRole(1L, roleId);
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** @TenantId 字段无 setter（写入口径 12.4.1），测试反射注入归属标注。 */
    private static void setTenantId(SysUser user, String tenantCode) {
        try {
            var field = SysUser.class.getDeclaredField("tenantId");
            field.setAccessible(true);
            field.set(user, tenantCode);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
