package com.acme.platform.system.security;

import com.acme.common.context.UserContext;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限判定服务（架构设计 5.2 核心逻辑，AC-009 越权拒绝）：
 * 角色编码集（令牌 claims）→ 本租户启用角色 → 角色绑定权限点 → 编码对照。
 * 角色编码仅为查询键、不是信任锚：不做任何编码短路放行——超管能力来自种子
 * 对内置 super_admin 角色的全量权限点绑定（V2/V3/V4），伪造同名编码的角色
 * 无绑定即无权限（review 修复 P0：原字符串短路可被租户自建同名角色利用提权）。
 */
@Component
public class PermissionDecisionService {

    /** 内置超管角色编码（保留编码：种子专用 + RoleUseCase 保留码校验引用，8.2.1）。 */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysPermissionRepository permissionRepository;

    public PermissionDecisionService(SysRoleRepository roleRepository,
                                     SysRolePermissionRepository rolePermissionRepository,
                                     SysPermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
    }

    /** 判定角色集是否具备权限点（角色集取令牌 claims 或内部调用主体）。 */
    public boolean hasAnyPermission(Set<String> roleCodes, String permissionCode) {
        if (roleCodes == null || roleCodes.isEmpty() || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        Set<Long> permissionIds = resolvePermissionIds(roleCodes);
        if (permissionIds.isEmpty()) {
            return false;
        }
        return permissionRepository.findByIdInAndStatus(permissionIds, EnableStatus.ENABLED).stream()
                .anyMatch(permission -> permissionCode.equals(permission.getPermissionCode()));
    }

    /** 当前请求主体判定（未认证主体一律拒绝，公开端点不经本面）。 */
    public boolean currentSubjectHas(String permissionCode) {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return false;
        }
        return hasAnyPermission(context.roles(), permissionCode);
    }

    /**
     * 当前主体实查内置角色编码集（REQ-20261117-025200 超管跨租户切换前置校验，确认记录④）：
     * roles 仅查询键（不作信任锚），按编码定位内置角色行（built_in 权威标记，跨租户旁路查询由
     * 仓储实现 @IgnoreTenant 声明）；未认证主体 / 空 roles 返回空集。
     */
    public Set<String> currentSubjectBuiltinRoles() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return Set.of();
        }
        return builtInRoleCodesOf(context.roles());
    }

    /** 角色编码集 → 命中的内置角色编码集（无命中返回空集）。 */
    public Set<String> builtInRoleCodesOf(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (SysRole role : roleRepository.findBuiltInByRoleCodeIn(roleCodes)) {
            codes.add(role.getRoleCode());
        }
        return codes;
    }

    /** 聚合角色集的 API 权限点编码全集（/me 视图用）。 */
    public Set<String> permissionCodesOfRoles(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> permissionIds = resolvePermissionIds(roleCodes);
        if (permissionIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (var permission : permissionRepository.findByIdIn(permissionIds)) {
            if (permission.getStatus() == EnableStatus.ENABLED) {
                codes.add(permission.getPermissionCode());
            }
        }
        return codes;
    }

    /**
     * 角色编码集 → 本租户启用角色 id 集（S14 收敛：权限判定与 /me 菜单解析共用主路径，
     * 租户过滤 12.4.1；无命中返回空集）。
     */
    public List<Long> enabledRoleIdsOf(Set<String> roleCodes) {
        List<Long> roleIds = new ArrayList<>();
        for (SysRole role : roleRepository.findByRoleCodeInAndStatus(roleCodes, EnableStatus.ENABLED)) {
            roleIds.add(role.getId());
        }
        return roleIds;
    }

    /**
     * 角色编码集 → 命中内置角色行 id 集（S14 收敛：内置角色跨租户旁路查询单源——
     * built_in 行仅系统种子路径可建，防提权语义不回退；/me 切换会话菜单回退用；无命中返回空集）。
     */
    public List<Long> builtInRoleIdsOf(Set<String> roleCodes) {
        List<Long> roleIds = new ArrayList<>();
        for (SysRole role : roleRepository.findBuiltInByRoleCodeIn(roleCodes)) {
            roleIds.add(role.getId());
        }
        return roleIds;
    }

    /**
     * 角色集 → 启用角色绑定的权限点 id 集（无启用角色或无绑定返回空集）：
     * ① 本租户启用角色（主路径，租户过滤 12.4.1）；
     * ② 内置角色跨租户兜底（REQ-20261114-120104 AC-6 跨租户管理面 + SystemIntegrationTest tenant-a
     *    上下文先例口径）：内置角色行仅系统种子路径可建、保留编码防租户自建同名提权（review P0 语义
     *    不回退——非内置角色仍严格本租户解析），使内置超管在任意租户上下文持全量权限点绑定。
     */
    private Set<Long> resolvePermissionIds(Set<String> roleCodes) {
        Set<Long> roleIds = new LinkedHashSet<>(enabledRoleIdsOf(roleCodes));
        roleIds.addAll(builtInRoleIdsOf(roleCodes));
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> permissionIds = new LinkedHashSet<>();
        for (var binding : rolePermissionRepository.findByRoleIdIn(roleIds)) {
            permissionIds.add(binding.getPermissionId());
        }
        return permissionIds;
    }

    /** 未命中即抛（拦截器与内部判定共用，AC-009）。 */
    public void checkCurrentSubject(String permissionCode) {
        if (!currentSubjectHas(permissionCode)) {
            throw new com.acme.common.core.BizException(SystemError.PERMISSION_DENIED);
        }
    }
}
