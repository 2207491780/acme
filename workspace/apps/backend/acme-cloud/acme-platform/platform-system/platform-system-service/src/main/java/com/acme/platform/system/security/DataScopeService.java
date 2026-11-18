package com.acme.platform.system.security;

import com.acme.common.context.UserContext;
import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataScope;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.framework.security.support.DataScopeResolver;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleDept;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 数据范围解析服务（REQ-20261114-120104 确认记录①②；走库模式 PermissionDecisionService 同款，
 * 无远程调用 M-05）：主体角色编码集（令牌 claims，非信任锚）→ 本租户启用角色 → 角色 data_scope 五档
 * → 可见部门/用户集。
 *
 * <p>合并口径（实现要点 5）：任一角色 ALL 档 → 整体不受限；否则档位标签取最宽档
 * （宽序 ALL &gt; DEPT_AND_CHILD &gt; DEPT &gt; CUSTOM &gt; SELF，见 DataScope.rank），可见集取
 * 各角色并集（并集语义 ≥ 任一单角色，标签仅承载口径）。角色解析含内置角色跨租户兜底
 * （与 PermissionDecisionService 同款口径，built_in 行仅系统种子路径可建）；无可解析角色
 * （含未认证上下文）→ SELF 档 fail-closed（与审计原「无 all 权限点 → 本人视图」语义一致）。</p>
 *
 * <p>部门子树解析：sys_dept.path 物化路径前缀 LIKE（"/1/%" 命中自身与子孙，尾斜杠杜绝
 * "/10/" 误匹配 "/100/"），单查询完成子树展开，免递归逐层走库；path 由部门用例在创建 /
 * 重挂父时整体重写保持一致。</p>
 */
@Component
public class DataScopeService implements DataScopeResolver {

    private final SysRoleRepository roleRepository;
    private final SysRoleDeptRepository roleDeptRepository;
    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    public DataScopeService(SysRoleRepository roleRepository, SysRoleDeptRepository roleDeptRepository,
                            SysDeptRepository deptRepository, SysUserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.roleDeptRepository = roleDeptRepository;
        this.deptRepository = deptRepository;
        this.userRepository = userRepository;
    }

    /** 当前主体（UserContext）数据范围判定：部门集已展开；SELF 档外不解析用户集（用户列表 SQL 按 dept_id 过滤）。 */
    public DataScopeDecision currentSubject() {
        return resolveFor(currentUserId(), currentRoles(), false);
    }

    /**
     * 当前主体判定并解析部门→用户可见集（跨域内部端点供审计 operator_id 集合过滤用）。
     * 性能注记：可见用户集为部门内全量用户展开，示范域（审计集合过滤）按租户规模可承受；
     * 超大规模租户应改为把 dept 集下推至消费域 SQL join，避免集合跨服务传输。
     */
    public DataScopeDecision currentSubjectWithUserIds() {
        return resolveFor(currentUserId(), currentRoles(), true);
    }

    /** 框架扩展点适配（上下文要素 → 走库解析；部门集已展开，用户集仅 SELF 个体）。 */
    @Override
    public DataScopeDecision resolve(DataPermissionContext context) {
        return resolveFor(context.userId(), context.roles(), false);
    }

    private DataScopeDecision resolveFor(String userId, Set<String> roleCodes, boolean withUserIds) {
        Set<String> roles = roleCodes == null ? Set.of() : roleCodes;
        // 角色解析：① 本租户启用角色（主路径，租户过滤 12.4.1）+ ② 内置角色跨租户兜底
        // （与 PermissionDecisionService 同款口径：built_in 行仅系统种子路径可建，租户自建角色不进兜底）
        java.util.LinkedHashMap<Long, SysRole> resolved = new java.util.LinkedHashMap<>();
        if (!roles.isEmpty()) {
            for (SysRole role : roleRepository.findByRoleCodeInAndStatus(roles, EnableStatus.ENABLED)) {
                resolved.put(role.getId(), role);
            }
            for (SysRole role : roleRepository.findBuiltInByRoleCodeIn(roles)) {
                resolved.putIfAbsent(role.getId(), role);
            }
        }
        if (resolved.isEmpty()) {
            // 无可解析角色（未认证上下文 / 未知角色编码 / 跨租户角色不命中）：SELF fail-closed
            return selfDecision(userId);
        }
        Set<Long> deptIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        DataScope widest = null;
        for (SysRole role : resolved.values()) {
            // 列缺省 / 脏数据 → 缺省 ALL（存量角色语义不变，V9 可空列代码级兜底）
            DataScope scope = Optional.ofNullable(DataScope.parse(role.getDataScope())).orElse(DataScope.DEFAULT);
            if (scope == DataScope.ALL) {
                return DataScopeDecision.all();
            }
            widest = widest == null ? scope : DataScope.widest(widest, scope);
            accumulate(role, scope, userId, deptIds, userIds, withUserIds);
        }
        return DataScopeDecision.of(widest, deptIds, userIds);
    }

    /** 单角色可见集累积（部门档展开部门集，SELF 档并入本人；withUserIds 时解析部门内用户）。 */
    private void accumulate(SysRole role, DataScope scope, String userId,
                            Set<Long> deptIds, Set<String> userIds, boolean withUserIds) {
        switch (scope) {
            case SELF -> userIds.add(userId);
            case DEPT -> subjectDept(userId).ifPresent(dept -> {
                deptIds.add(dept.getId());
                if (withUserIds) {
                    collectDeptUsers(Set.of(dept.getId()), userIds);
                }
            });
            case DEPT_AND_CHILD -> subjectDept(userId).ifPresent(dept -> {
                Set<Long> subtree = subtreeIds(dept);
                deptIds.addAll(subtree);
                if (withUserIds) {
                    collectDeptUsers(subtree, userIds);
                }
            });
            case CUSTOM -> {
                Set<Long> custom = roleDeptRepository.findByRoleId(role.getId()).stream()
                        .map(SysRoleDept::getDeptId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                deptIds.addAll(custom);
                if (withUserIds) {
                    collectDeptUsers(custom, userIds);
                }
            }
            case ALL -> throw new IllegalStateException("ALL 档已在合并短路处理，不可达");
        }
    }

    /** 主体归属部门（用户不存在 / 未归属部门 → 空，档位按空可见集收敛）。 */
    private Optional<SysDept> subjectDept(String userId) {
        Long id = parseId(userId);
        if (id == null) {
            return Optional.empty();
        }
        return userRepository.findById(id).map(SysUser::getDeptId).flatMap(Optional::ofNullable)
                .flatMap(deptRepository::findById);
    }

    /** 子树 id 集（含自身；path 前缀 LIKE 单查询，性能注记见类注）。 */
    private Set<Long> subtreeIds(SysDept dept) {
        Set<Long> ids = new LinkedHashSet<>();
        for (SysDept node : deptRepository.findSubtree(dept.getPath())) {
            ids.add(node.getId());
        }
        return ids;
    }

    private void collectDeptUsers(Set<Long> deptIds, Set<String> userIds) {
        for (SysUser user : userRepository.findByDeptIdIn(deptIds)) {
            userIds.add(String.valueOf(user.getId()));
        }
    }

    private static DataScopeDecision selfDecision(String userId) {
        return userId == null || userId.isBlank()
                ? new DataScopeDecision(DataScope.SELF, Set.of(), Set.of())
                : DataScopeDecision.of(DataScope.SELF, Set.of(), Set.of(userId));
    }

    private static Long parseId(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String currentUserId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }

    private static Set<String> currentRoles() {
        UserContext context = UserContext.get();
        return context == null ? Set.of() : context.roles();
    }
}
