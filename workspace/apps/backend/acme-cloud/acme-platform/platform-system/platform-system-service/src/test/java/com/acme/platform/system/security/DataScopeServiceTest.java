package com.acme.platform.system.security;

import com.acme.common.context.UserContext;
import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataScope;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleDept;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据范围解析服务测试（REQ-20261114-120104，AC-4 解析口径：五档 → 可见集；合并最宽档 + 并集；
 * 无角色 fail-closed SELF；子树 path 前缀展开）。
 */
class DataScopeServiceTest {

    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysRoleDeptRepository roleDeptRepository = mock(SysRoleDeptRepository.class);
    private final SysDeptRepository deptRepository = mock(SysDeptRepository.class);
    private final SysUserRepository userRepository = mock(SysUserRepository.class);

    private final DataScopeService service = new DataScopeService(
            roleRepository, roleDeptRepository, deptRepository, userRepository);

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void noResolvableRolesFailsClosedToSelf() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("unknown"), EnableStatus.ENABLED))
                .thenReturn(List.of());
        DataScopeDecision decision = service.resolve(context("42", Set.of("unknown")));
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
        assertThat(decision.visibleDeptIds()).isEmpty();
    }

    @Test
    void allTierUnrestricted() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "ALL")));
        assertThat(service.resolve(context("42", Set.of("ops"))).unrestricted()).isTrue();
    }

    @Test
    void deptTierResolvesSubjectDeptOnly() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "DEPT")));
        stubSubjectUser(42L, 11L);
        SysDept dept = dept(11L, "/11/");
        when(deptRepository.findById(11L)).thenReturn(Optional.of(dept));

        DataScopeDecision decision = service.resolve(context("42", Set.of("ops")));
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT);
        assertThat(decision.visibleDeptIds()).containsExactly(11L);
    }

    @Test
    void deptAndChildTierExpandsSubtreeByPath() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "DEPT_AND_CHILD")));
        stubSubjectUser(42L, 11L);
        SysDept dept = dept(11L, "/11/");
        when(deptRepository.findById(11L)).thenReturn(Optional.of(dept));
        // path 前缀 LIKE 单查询展开（含自身与子孙）
        when(deptRepository.findSubtree("/11/")).thenReturn(List.of(dept, dept(12L, "/11/12/")));

        DataScopeDecision decision = service.resolve(context("42", Set.of("ops")));
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT_AND_CHILD);
        assertThat(decision.visibleDeptIds()).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    void customTierReadsRoleDeptBindings() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "CUSTOM")));
        when(roleDeptRepository.findByRoleId(2L)).thenReturn(List.of(
                new SysRoleDept(2L, 31L), new SysRoleDept(2L, 32L)));

        DataScopeDecision decision = service.resolve(context("42", Set.of("ops")));
        assertThat(decision.scope()).isEqualTo(DataScope.CUSTOM);
        assertThat(decision.visibleDeptIds()).containsExactlyInAnyOrder(31L, 32L);
    }

    @Test
    void selfTierSeesOnlySelf() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "SELF")));
        DataScopeDecision decision = service.resolve(context("42", Set.of("ops")));
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
        assertThat(decision.visibleDeptIds()).isEmpty();
    }

    @Test
    void missingSubjectDeptConvergesToEmptyVisibleSet() {
        // 主体未归属部门：DEPT 档可见集为空（消费方空页 fail-closed）
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "DEPT")));
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        DataScopeDecision decision = service.resolve(context("42", Set.of("ops")));
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT);
        assertThat(decision.visibleDeptIds()).isEmpty();
        assertThat(decision.visibleUserIds()).isEmpty();
    }

    @Test
    void multiRoleMergeTakesWidestTierAndUnionSets() {
        // DEPT 角色并入部门可见集，SELF 角色并入本人：标签取最宽 DEPT，并集 ≥ 任一单角色
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops", "viewer"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "DEPT"), role(3L, "viewer", "SELF")));
        stubSubjectUser(42L, 11L);
        when(deptRepository.findById(11L)).thenReturn(Optional.of(dept(11L, "/11/")));

        DataScopeDecision decision = service.resolve(context("42", Set.of("ops", "viewer")));
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT);
        assertThat(decision.visibleDeptIds()).containsExactly(11L);
        assertThat(decision.visibleUserIds()).containsExactly("42");
    }

    @Test
    void anyAllRoleShortCircuitsUnrestricted() {
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops", "boss"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "SELF"), role(3L, "boss", "ALL")));
        assertThat(service.resolve(context("42", Set.of("ops", "boss"))).unrestricted()).isTrue();
    }

    @Test
    void withUserIdsResolvesDeptUsersForCollectionFilter() {
        // 跨域内部端点口径：部门档展开部门内用户（审计 operator_id 集合过滤）；
        // currentSubjectWithUserIds 读 UserContext，须绑定主体（与 currentSubjectReadsUserContextClaims 同口径）
        UserContext.bind(new UserContext("42", Set.of("ops")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "DEPT")));
        stubSubjectUser(42L, 11L);
        when(deptRepository.findById(11L)).thenReturn(Optional.of(dept(11L, "/11/")));
        when(userRepository.findByDeptIdIn(Set.of(11L))).thenReturn(List.of(
                user(42L, 11L), user(43L, 11L)));

        DataScopeDecision decision = service.currentSubjectWithUserIds();
        assertThat(decision.scope()).isEqualTo(DataScope.DEPT);
        assertThat(decision.visibleDeptIds()).containsExactly(11L);
        assertThat(decision.visibleUserIds()).containsExactlyInAnyOrder("42", "43");
    }

    @Test
    void nullColumnValueDefaultsToAllTier() {
        // 脏数据兜底：data_scope 列 null → 缺省 ALL（存量角色语义不变）
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", null)));
        assertThat(service.resolve(context("42", Set.of("ops"))).unrestricted()).isTrue();
    }

    @Test
    void currentSubjectReadsUserContextClaims() {
        UserContext.bind(new UserContext("42", Set.of("ops")));
        when(roleRepository.findByRoleCodeInAndStatus(Set.of("ops"), EnableStatus.ENABLED))
                .thenReturn(List.of(role(2L, "ops", "SELF")));
        DataScopeDecision decision = service.currentSubject();
        assertThat(decision.scope()).isEqualTo(DataScope.SELF);
        assertThat(decision.visibleUserIds()).containsExactly("42");
    }

    private static DataPermissionContext context(String userId, Set<String> roles) {
        return new DataPermissionContext("any.key", "DataScopeServiceTest", userId, "default", roles);
    }

    private static SysRole role(Long id, String code, String dataScope) {
        SysRole role = new SysRole(code, code, null, false);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        role.setDataScope(dataScope);
        return role;
    }

    private static SysDept dept(Long id, String path) {
        SysDept dept = new SysDept(0L, "部门", 0, EnableStatus.ENABLED);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(dept, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        dept.setPath(path);
        return dept;
    }

    private static SysUser user(Long id, Long deptId) {
        SysUser user = new SysUser("u" + id, "用户" + id, null, null, false);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        user.setDeptId(deptId);
        return user;
    }

    private void stubSubjectUser(long userId, long deptId) {
        SysUser subject = user(userId, deptId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(subject));
    }
}
