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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限判定服务（架构设计 5.2 核心逻辑，AC-009 越权拒绝）：
 * 角色编码集（令牌 claims）→ 本租户启用角色 → 角色绑定权限点 → 编码对照；
 * 内置超管 super_admin 短路放行（种子绑定全量权限点，二者等价双保险）。
 */
@Component
public class PermissionDecisionService {

    /** 内置超管角色编码（仅存在于种子数据，代码仅引用常量判定，8.2.1）。 */
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
        if (roleCodes.contains(SUPER_ADMIN_ROLE)) {
            return true;
        }
        List<SysRole> roles = roleRepository.findByRoleCodeInAndStatus(roleCodes, EnableStatus.ENABLED);
        if (roles.isEmpty()) {
            return false;
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        Set<Long> permissionIds = new LinkedHashSet<>();
        for (var binding : rolePermissionRepository.findByRoleIdIn(roleIds)) {
            permissionIds.add(binding.getPermissionId());
        }
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

    /** 聚合角色集的 API 权限点编码全集（/me 视图用）。 */
    public Set<String> permissionCodesOfRoles(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Collections.emptySet();
        }
        if (roleCodes.contains(SUPER_ADMIN_ROLE)) {
            return permissionRepository.findByStatus(EnableStatus.ENABLED).stream()
                    .map(permission -> permission.getPermissionCode())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        List<SysRole> roles = roleRepository.findByRoleCodeInAndStatus(roleCodes, EnableStatus.ENABLED);
        if (roles.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        Set<Long> permissionIds = new LinkedHashSet<>();
        for (var binding : rolePermissionRepository.findByRoleIdIn(roleIds)) {
            permissionIds.add(binding.getPermissionId());
        }
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

    /** 未命中即抛（拦截器与内部判定共用，AC-009）。 */
    public void checkCurrentSubject(String permissionCode) {
        if (!currentSubjectHas(permissionCode)) {
            throw new com.acme.common.core.BizException(SystemError.PERMISSION_DENIED);
        }
    }
}
