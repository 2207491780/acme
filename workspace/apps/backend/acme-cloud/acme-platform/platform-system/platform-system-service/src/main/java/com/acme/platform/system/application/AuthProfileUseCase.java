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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 登录认证画像用例（架构设计 3.3 / 4.2，FR-001）：
 * 按 username 全局定位（@IgnoreTenant 显式声明 + 审计，12.4.2）；无租户上下文的服务身份调用（Q-D08）。
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

    /** 按用户名查认证画像；查无此用户抛 USER_NOT_FOUND（404，消费方映射统一失败 FR-005）。 */
    @IgnoreTenant("login-profile-lookup")
    public AuthProfileView findByUsername(String username) {
        SysUser user = userRepository.findByUsername(username);
        if (user == null) {
            throw new BizException(SystemError.USER_NOT_FOUND);
        }
        Set<String> roleCodes = new LinkedHashSet<>();
        List<Long> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                .map(SysUserRole::getRoleId).toList();
        if (!roleIds.isEmpty()) {
            for (SysRole role : roleRepository.findAllById(roleIds)) {
                roleCodes.add(role.getRoleCode());
            }
        }
        return new AuthProfileView(String.valueOf(user.getId()), user.getUsername(), user.getNickname(),
                user.getTenantId(), effectiveStatus(user).name(), roleCodes);
    }

    /** 生效状态：用户或所属租户任一停用即停用（停用级联，登录链统一拒绝口径）。 */
    private EnableStatus effectiveStatus(SysUser user) {
        if (user.getStatus() == EnableStatus.DISABLED) {
            return EnableStatus.DISABLED;
        }
        Optional<SysTenant> tenant = tenantRepository.findByTenantCode(user.getTenantId());
        return tenant.map(value -> value.getStatus() == EnableStatus.DISABLED
                ? EnableStatus.DISABLED : user.getStatus())
                .orElse(user.getStatus());
    }
}
