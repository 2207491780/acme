package com.acme.platform.system.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.security.PermissionDecisionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 当前登录者视图用例单元测试（纯 JUnit 无 Spring）：
 * REQ-20261117-025200 切换会话跨租户回退分支（确认记录④）——本租户角色未命中时仅对 claims 中
 * 命中的内置角色取跨租户行（非内置角色码丢弃），菜单走内置角色绑定旁路面；本租户命中走既有口径。
 * save/查询桩镜像仓储语义（RoleAndTenantUseCaseTest 同款）。
 */
class MeUseCaseTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysDeptRepository deptRepository = mock(SysDeptRepository.class);
    private final MenuUseCase menuUseCase = mock(MenuUseCase.class);
    private final PermissionDecisionService decisionService = mock(PermissionDecisionService.class);
    private final MeUseCase useCase = new MeUseCase(userRepository, roleRepository, deptRepository,
            menuUseCase, decisionService);

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private void bindOperator(String userId, Set<String> roles) {
        UserContext.bind(new UserContext(userId, roles));
    }

    private static SysUser user(Long id, String username) {
        SysUser user = new SysUser(username, username, null, null, false);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return user;
    }

    private static SysRole builtInRole(Long id, String code) {
        SysRole role = new SysRole(SysRole.ROOT_PARENT_ID, code, code, null, true);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return role;
    }

    @Test
    void should_fallbackToBuiltInRolesAndBypassMenuLookup_when_localRoleLookupMisses() {
        // 切换会话（claims=super_admin、上下文=目标租户）：本租户角色未命中 → 仅内置角色跨租户回退，
        // 菜单走 visibleMenuIdsOfBuiltIn 旁路面；user 行走 PK 全局直读（findByIdGlobal）
        bindOperator("1", Set.of("super_admin"));
        when(userRepository.findByIdGlobal(1L)).thenReturn(Optional.of(user(1L, "admin")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("super_admin"), EnableStatus.ENABLED))
                .thenReturn(List.of());
        when(roleRepository.findBuiltInByRoleCodeIn(Set.of("super_admin")))
                .thenReturn(List.of(builtInRole(1L, "super_admin")));
        when(decisionService.permissionCodesOfRoles(Set.of("super_admin")))
                .thenReturn(Set.of("system:user:list"));
        when(menuUseCase.visibleMenuIdsOfBuiltIn(List.of(1L))).thenReturn(Set.of(1L, 10L));

        MeUseCase.MeSnapshot snapshot = useCase.loadMe();

        assertThat(snapshot.user().getUsername()).isEqualTo("admin");
        assertThat(snapshot.permissions()).containsExactly("system:user:list");
        assertThat(snapshot.menuIds()).containsExactlyInAnyOrder(1L, 10L);
        verify(userRepository).findByIdGlobal(1L);
        verify(userRepository, never()).findById(any());
        // 防提权口径：本租户路径不进入，非内置回退不触发本租户菜单面
        verify(menuUseCase, never()).visibleMenuIdsOf(any());
    }

    @Test
    void should_useLocalRolesAndTenantScopedMenus_when_localRoleLookupHits() {
        // 本租户命中（常态会话）：既有口径不变——本租户角色 id + 租户过滤菜单绑定，不触发内置旁路
        bindOperator("7", Set.of("it_viewer"));
        when(userRepository.findByIdGlobal(7L)).thenReturn(Optional.of(user(7L, "viewer")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("it_viewer"), EnableStatus.ENABLED))
                .thenReturn(List.of(builtInRole(9L, "it_viewer")));
        when(decisionService.permissionCodesOfRoles(Set.of("it_viewer"))).thenReturn(Set.of());
        when(menuUseCase.visibleMenuIdsOf(List.of(9L))).thenReturn(Set.of(2L));

        MeUseCase.MeSnapshot snapshot = useCase.loadMe();

        assertThat(snapshot.user().getUsername()).isEqualTo("viewer");
        assertThat(snapshot.menuIds()).containsExactly(2L);
        verify(menuUseCase).visibleMenuIdsOf(List.of(9L));
        verify(menuUseCase, never()).visibleMenuIdsOfBuiltIn(any());
        verify(roleRepository, never()).findBuiltInByRoleCodeIn(anySet());
    }

    @Test
    void should_dropNonBuiltInRoleCodes_when_fallbackResolvesBuiltInRolesOnly() {
        // 防提权口径（PermissionDecisionService 同款）：回退仅取 findBuiltInByRoleCodeIn 命中的内置角色行，
        // 伪造同名非内置编码被丢弃（查询面 built_in=true 过滤承担）
        bindOperator("7", Set.of("ops", "super_admin"));
        when(userRepository.findByIdGlobal(7L)).thenReturn(Optional.of(user(7L, "viewer")));
        when(roleRepository.findByRoleCodeInAndStatus(anySet(), any())).thenReturn(List.of());
        when(roleRepository.findBuiltInByRoleCodeIn(Set.of("ops", "super_admin")))
                .thenReturn(List.of(builtInRole(1L, "super_admin")));
        when(decisionService.permissionCodesOfRoles(anySet())).thenReturn(Set.of());
        when(menuUseCase.visibleMenuIdsOfBuiltIn(List.of(1L))).thenReturn(Set.of(1L));

        MeUseCase.MeSnapshot snapshot = useCase.loadMe();

        assertThat(snapshot.menuIds()).containsExactly(1L);
        verify(menuUseCase).visibleMenuIdsOfBuiltIn(List.of(1L));
    }

    @Test
    void should_throwUserNotFound_when_userRowMissingGlobally() {
        // PK 全局直读仍无行（脏数据）→ 既有 USER_NOT_FOUND 显式失败
        bindOperator("404", Set.of("super_admin"));
        when(userRepository.findByIdGlobal(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.loadMe())
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.USER_NOT_FOUND.code()));
    }

    @Test
    void should_rejectWithoutLookup_when_contextMissing() {
        // 未认证主体：既有 PERMISSION_DENIED 短路（不触达任何查询）
        assertThatThrownBy(() -> useCase.loadMe())
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.PERMISSION_DENIED.code()));
        verify(userRepository, never()).findByIdGlobal(any());
    }

    // ---------- 部门名供给（REQ-20261117-180000 水印模板 {deptName}） ----------

    private void stubLocalRoleChain(String userId, Set<String> roles) {
        when(userRepository.findByIdGlobal(Long.valueOf(userId))).thenReturn(Optional.of(user(Long.valueOf(userId), "viewer")));
        when(roleRepository.findByRoleCodeInAndStatus(roles, EnableStatus.ENABLED))
                .thenReturn(List.of(builtInRole(9L, roles.iterator().next())));
        when(decisionService.permissionCodesOfRoles(roles)).thenReturn(Set.of());
        when(menuUseCase.visibleMenuIdsOf(List.of(9L))).thenReturn(Set.of());
    }

    private static com.acme.platform.system.domain.SysDept dept(Long id, String name) {
        var dept = new com.acme.platform.system.domain.SysDept(0L, name, 0, EnableStatus.ENABLED);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(dept, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return dept;
    }

    @Test
    void should_resolveDeptNameGlobally_when_userHasDept() {
        // deptId 来自全局直读的 user 行 → 部门名走同款 PK 全局旁路（切换会话下部门归属源租户）
        bindOperator("7", Set.of("it_viewer"));
        stubLocalRoleChain("7", Set.of("it_viewer"));
        SysUser withDept = user(7L, "viewer");
        withDept.setDeptId(5L);
        when(userRepository.findByIdGlobal(7L)).thenReturn(Optional.of(withDept));
        when(deptRepository.findByIdGlobal(5L)).thenReturn(Optional.of(dept(5L, "研发部")));

        MeUseCase.MeSnapshot snapshot = useCase.loadMe();

        assertThat(snapshot.deptName()).isEqualTo("研发部");
        verify(deptRepository).findByIdGlobal(5L);
        verify(deptRepository, never()).findById(any());
    }

    @Test
    void should_returnNullDeptName_when_deptRowMissing() {
        // 部门行缺失（脏数据/已删除）→ null，不阻断 /me
        bindOperator("7", Set.of("it_viewer"));
        stubLocalRoleChain("7", Set.of("it_viewer"));
        SysUser withDept = user(7L, "viewer");
        withDept.setDeptId(5L);
        when(userRepository.findByIdGlobal(7L)).thenReturn(Optional.of(withDept));
        when(deptRepository.findByIdGlobal(5L)).thenReturn(Optional.empty());

        assertThat(useCase.loadMe().deptName()).isNull();
    }

    @Test
    void should_skipDeptLookup_when_userHasNoDept() {
        // 未分配部门：不触达部门查询，deptName=null
        bindOperator("7", Set.of("it_viewer"));
        stubLocalRoleChain("7", Set.of("it_viewer"));

        assertThat(useCase.loadMe().deptName()).isNull();
        verify(deptRepository, never()).findByIdGlobal(any());
    }
}
