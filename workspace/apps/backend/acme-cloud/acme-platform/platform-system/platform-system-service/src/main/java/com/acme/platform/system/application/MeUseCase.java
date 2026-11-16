package com.acme.platform.system.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.security.PermissionDecisionService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前登录者视图用例（FR-008 权限声明与菜单可见性）：权限声明经令牌（roles claims）与上下文链路解析，
 * 菜单按角色绑定过滤（菜单级权限）；视图组装在接口层（11.5.3）。
 * REQ-20261117-025200：切换会话（仅内置 super_admin 可达，确认记录④）身份跨租户解析——
 * user 行 PK 全局直读、角色/菜单解析内置角色跨租户回退（详见 loadMe 注）。
 */
@Service
public class MeUseCase {

    /** 当前登录者快照（user=本租户用户主数据；roles=令牌 claims；menuIds=可见菜单并集）。 */
    public record MeSnapshot(SysUser user, Set<String> roles, Set<String> permissions, Set<Long> menuIds) {
    }

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final MenuUseCase menuUseCase;
    private final PermissionDecisionService decisionService;

    public MeUseCase(SysUserRepository userRepository, SysRoleRepository roleRepository,
                     MenuUseCase menuUseCase, PermissionDecisionService decisionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuUseCase = menuUseCase;
        this.decisionService = decisionService;
    }

    /**
     * 汇聚当前登录者信息（用户已被网关+security 校验；userId 取自上下文）。
     * REQ-20261117-025200 切换会话口径（确认记录④）：超管切换到目标租户后 user 行按 PK 全局直读
     * （id 来自已验签令牌，租户过滤对 PK 等值查询是冗余隔离）；角色解析先本租户、未命中时仅对
     * claims 中命中的内置角色取跨租户行（防提权口径与 PermissionDecisionService 同款——
     * 非内置角色码一律丢弃）；菜单按回退后的内置角色 id 跨租户取绑定行（MenuUseCase 旁路面）；
     * permissions 链路经 decisionService（内置角色回退 + 绑定旁路既有），本用例不改其语义。
     */
    public MeSnapshot loadMe() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new BizException(SystemError.PERMISSION_DENIED);
        }
        Long userId = Long.valueOf(context.userId());
        SysUser user = userRepository.findByIdGlobal(userId)
                .orElseThrow(() -> new BizException(SystemError.USER_NOT_FOUND));
        Set<String> roles = context.roles();
        Set<String> permissions = decisionService.permissionCodesOfRoles(roles);
        List<Long> roleIds = roleRepository.findByRoleCodeInAndStatus(roles, EnableStatus.ENABLED).stream()
                .map(SysRole::getId).toList();
        Set<Long> menuIds;
        if (!roleIds.isEmpty()) {
            // 本租户命中：绑定行同租户读取（既有口径不变）
            menuIds = menuUseCase.visibleMenuIdsOf(roleIds);
        } else {
            // 切换会话回退（REQ-20261117-025200，确认记录④）：仅 claims 中命中内置角色行的编码
            // 取其跨租户行（findBuiltInByRoleCodeIn 旁路+审计），非内置角色码一律丢弃（防提权不回退）
            List<Long> builtInRoleIds = roleRepository.findBuiltInByRoleCodeIn(roles).stream()
                    .map(SysRole::getId).toList();
            menuIds = menuUseCase.visibleMenuIdsOfBuiltIn(builtInRoleIds);
        }
        return new MeSnapshot(user, new LinkedHashSet<>(roles), permissions, menuIds);
    }
}
