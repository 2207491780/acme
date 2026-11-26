package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.IgnoreTenant;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 登录认证画像用例（架构设计 3.3 / 4.2，FR-001）：
 * 按 (tenantCode, username) 域内定位（REQ-20261125-001738 确认②；@IgnoreTenant 显式声明 + 审计，12.4.2，
 * 域隔离由显式 tenantCode 查参承载）；无租户上下文的服务身份调用（Q-D08）。
 * 停用级联（tenant-management-complete）：所属租户停用 → 画像状态归一 DISABLED（统一停用语义，
 * 消费方 auth 零改动映射 ACCOUNT_DISABLED）；租户目录缺失（脏数据）按用户自身状态判定。
 */
@Service
public class AuthProfileUseCase {

    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final SysTenantRepository tenantRepository;

    public AuthProfileUseCase(SysUserRepository userRepository, SysUserRoleRepository userRoleRepository,
                              SysRoleRepository roleRepository, SysTenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * 按 (tenantCode, username) 域内查认证画像；未知租户或域内查无此用户抛 USER_NOT_FOUND
     * （404，消费方映射统一失败 FR-005，不泄露差异）。
     */
    @IgnoreTenant("login-profile-lookup")
    public AuthProfileView findByUsername(String tenantCode, String username) {
        SysUser user = userRepository.findByUsername(tenantCode, username);
        if (user == null) {
            throw new BizException(SystemError.USER_NOT_FOUND);
        }
        List<SysUserRole> bindings = userRoleRepository.findByUserId(user.getId());
        Set<String> roleCodes = roleCodesOf(bindings, rolesByIdOf(bindings));
        return new AuthProfileView(String.valueOf(user.getId()), user.getUsername(), user.getNickname(),
                user.getTenantId(), effectiveStatus(user,
                () -> tenantRepository.findByTenantCode(user.getTenantId())).name(), roleCodes);
    }

    /**
     * 按 userId 批量查认证画像（REQ-20261114-120105：在线用户列表 username/超管判定富化）；
     * 不存在的 userId 不产生条目（消费方按缺省展示处理）。
     * S14 N+1 收敛：绑定行、角色行与租户行改批量查询（循环内逐用户走库改集合一次取回），
     * 各用户画像内容与单查口径逐字段一致（角色码集合无序契约）。
     */
    @IgnoreTenant("online-user-profile-lookup")
    public List<AuthProfileView> findByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = userIds.stream().distinct().toList();
        List<SysUserRole> bindings = userRoleRepository.findByUserIdIn(distinct);
        Map<Long, List<SysUserRole>> bindingsByUserId = bindings.stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId));
        Map<Long, SysRole> rolesById = rolesByIdOf(bindings);
        List<SysUser> users = userRepository.findAllById(distinct);
        Map<String, Optional<SysTenant>> tenantsByCode = tenantsByCodeOf(users);
        List<AuthProfileView> views = new ArrayList<>(distinct.size());
        for (SysUser user : users) {
            Set<String> roleCodes = roleCodesOf(
                    bindingsByUserId.getOrDefault(user.getId(), List.of()), rolesById);
            views.add(new AuthProfileView(String.valueOf(user.getId()), user.getUsername(), user.getNickname(),
                    user.getTenantId(),
                    effectiveStatus(user,
                            () -> tenantsByCode.getOrDefault(user.getTenantId(), Optional.empty())).name(),
                    roleCodes));
        }
        return views;
    }

    /** 批量用户 → 租户目录索引（S14 N+1 收敛：停用级联所需租户行一次取回；平台目录表无租户列）。 */
    private Map<String, Optional<SysTenant>> tenantsByCodeOf(List<SysUser> users) {
        List<String> tenantCodes = users.stream()
                .map(SysUser::getTenantId)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (tenantCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Optional<SysTenant>> tenantsByCode = new HashMap<>();
        for (SysTenant tenant : tenantRepository.findByTenantCodeIn(tenantCodes)) {
            tenantsByCode.putIfAbsent(tenant.getTenantCode(), Optional.of(tenant));
        }
        return tenantsByCode;
    }

    /** 绑定行 → 角色行索引（单查一次取回；角色行缺失（脏数据）跳过，与逐用户口径一致）。 */
    private Map<Long, SysRole> rolesByIdOf(List<SysUserRole> bindings) {
        List<Long> roleIds = bindings.stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SysRole> rolesById = new HashMap<>();
        for (SysRole role : roleRepository.findAllById(roleIds)) {
            rolesById.putIfAbsent(role.getId(), role);
        }
        return rolesById;
    }

    /** 绑定行 + 角色索引 → 角色编码集（编码集无序契约，消费方按 Set 消费）。 */
    private static Set<String> roleCodesOf(List<SysUserRole> bindings, Map<Long, SysRole> rolesById) {
        Set<String> roleCodes = new LinkedHashSet<>();
        for (SysUserRole binding : bindings) {
            SysRole role = rolesById.get(binding.getRoleId());
            if (role != null) {
                roleCodes.add(role.getRoleCode());
            }
        }
        return roleCodes;
    }

    /**
     * 生效状态：用户或所属租户任一停用即停用（停用级联，登录链统一拒绝口径）。
     * 租户行经 {@code tenantLookup} 供给（用户停用时短路不查，与既有惰性口径一致；
     * 批量面由调用方预取避免循环内逐用户走库）。
     */
    private EnableStatus effectiveStatus(SysUser user, Supplier<Optional<SysTenant>> tenantLookup) {
        if (user.getStatus() == EnableStatus.DISABLED) {
            return EnableStatus.DISABLED;
        }
        Optional<SysTenant> tenant = tenantLookup.get();
        return tenant.map(value -> value.getStatus() == EnableStatus.DISABLED
                ? EnableStatus.DISABLED : user.getStatus())
                .orElse(user.getStatus());
    }
}
