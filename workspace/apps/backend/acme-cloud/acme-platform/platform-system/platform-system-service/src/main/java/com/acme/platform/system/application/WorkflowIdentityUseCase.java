package com.acme.platform.system.application;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 服务间用户身份目录用例（REQ-20261121-142316：BPM 引擎 UserTask 候选解析与 SpEL 内置函数
 * hasRole/inDept 数据源；契约 WorkflowIdentityClient 服务端）。宽目录查询语义：
 * 未知用户/角色编码/部门返回空集（不抛 404），调用方按无候选/无权限 fail-closed 处置。
 * 租户口径：全部查询走上下文租户过滤（内部令牌随请求携带 tenantId）——流程实例候选解析
 * 天然同租户面。
 */
@Service
public class WorkflowIdentityUseCase {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;

    public WorkflowIdentityUseCase(SysUserRepository userRepository,
                                   SysRoleRepository roleRepository,
                                   SysUserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** 用户角色编码集（绑定角色行不限状态：编码仅查询键，引擎侧以启用角色过滤语义裁决）。 */
    public Set<String> userRoleCodes(Long userId) {
        List<Long> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(binding -> binding.getRoleId())
                .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(roleRepository.findAllById(roleIds).stream()
                .map(role -> role.getRoleCode())
                .toList());
    }

    /** 用户主属部门 id（用户不存在/未归属返回 null）。 */
    public Long userDeptId(Long userId) {
        return userRepository.findById(userId).map(user -> user.getDeptId()).orElse(null);
    }

    /** 按角色编码取用户 id 集（编码定位启用角色行，绑定行展开用户集；未知编码=空集）。 */
    public Set<Long> roleUserIds(String roleCode) {
        var roles = roleRepository.findByRoleCodeInAndStatus(List.of(roleCode), EnableStatus.ENABLED);
        if (roles.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(userRoleRepository.findByRoleIdIn(roles.stream().map(role -> role.getId()).toList())
                .stream().map(binding -> binding.getUserId()).toList());
    }

    /** 按部门 id 取用户 id 集（未知部门=空集）。 */
    public Set<Long> deptUserIds(Long deptId) {
        return Set.copyOf(userRepository.findByDeptIdIn(List.of(deptId)).stream()
                .map(user -> user.getId())
                .toList());
    }
}
