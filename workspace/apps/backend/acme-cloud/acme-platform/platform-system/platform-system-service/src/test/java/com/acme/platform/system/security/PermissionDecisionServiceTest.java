package com.acme.platform.system.security;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口级权限判定测试（架构设计 5.2；AC-009 越权拒绝）。
 */
class PermissionDecisionServiceTest {

    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
    private final SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);

    private final PermissionDecisionService service = new PermissionDecisionService(
            roleRepository, rolePermissionRepository, permissionRepository);

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void superAdminShortCircuits() {
        assertThat(service.hasAnyPermission(Set.of("super_admin"), "system:user:create")).isTrue();
    }

    @Test
    void grantedViaRoleBindingPasses() {
        UserContext.bind(new UserContext("1001", Set.of("ops")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(11L, "ops")));
        when(rolePermissionRepository.findByRoleIdIn(List.of(11L)))
                .thenReturn(List.of(new SysRolePermission(11L, 21L)));
        SysPermission permission = new SysPermission("system:user:create", "创建用户",
                com.acme.platform.system.api.PermissionType.API, "POST", "/admin/v1/system/users");
        setPermissionId(permission, 21L);
        when(permissionRepository.findByIdInAndStatus(anyCollection(), any()))
                .thenReturn(List.of(permission));
        assertThat(service.currentSubjectHas("system:user:create")).isTrue();
    }

    @Test
    void noBindingDenied403() {
        UserContext.bind(new UserContext("1001", Set.of("ops")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(11L, "ops")));
        when(rolePermissionRepository.findByRoleIdIn(List.of(11L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.checkCurrentSubject("system:user:create"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.PERMISSION_DENIED.code()));
    }

    @Test
    void unauthenticatedSubjectDenied() {
        assertThat(service.currentSubjectHas("system:user:create")).isFalse();
    }

    @Test
    void disabledRoleIgnored() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of());
        assertThat(service.hasAnyPermission(Set.of("ops"), "system:user:create")).isFalse();
    }

    @Test
    void permissionCodesAggregatedForMeView() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(11L, "ops")));
        when(rolePermissionRepository.findByRoleIdIn(List.of(11L)))
                .thenReturn(List.of(new SysRolePermission(11L, 21L), new SysRolePermission(11L, 22L)));
        SysPermission first = new SysPermission("system:user:list", "用户列表",
                com.acme.platform.system.api.PermissionType.API, "GET", "/x");
        setPermissionId(first, 21L);
        SysPermission second = new SysPermission("system:user:create", "创建用户",
                com.acme.platform.system.api.PermissionType.API, "POST", "/y");
        setPermissionId(second, 22L);
        when(permissionRepository.findByIdIn(anyCollection())).thenReturn(java.util.Set.of(first, second));
        assertThat(service.permissionCodesOfRoles(Set.of("ops")))
                .containsExactlyInAnyOrder("system:user:list", "system:user:create");
    }

    private static SysRole role(Long id, String code) {
        SysRole role = new SysRole(code, code, null, false);
        setRoleId(role, id);
        return role;
    }

    private static void setRoleId(SysRole role, Long id) {
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setPermissionId(SysPermission permission, Long id) {
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(permission, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
