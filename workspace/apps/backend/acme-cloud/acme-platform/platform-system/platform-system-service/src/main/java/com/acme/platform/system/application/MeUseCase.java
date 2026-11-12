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

    /** 汇聚当前登录者信息（用户已被网关+security 校验；userId 取自上下文）。 */
    public MeSnapshot loadMe() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new BizException(SystemError.PERMISSION_DENIED);
        }
        Long userId = Long.valueOf(context.userId());
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(SystemError.USER_NOT_FOUND));
        Set<String> roles = context.roles();
        Set<String> permissions = decisionService.permissionCodesOfRoles(roles);
        List<Long> roleIds = roleRepository.findByRoleCodeInAndStatus(roles, EnableStatus.ENABLED).stream()
                .map(SysRole::getId).toList();
        Set<Long> menuIds = menuUseCase.visibleMenuIdsOf(roleIds);
        return new MeSnapshot(user, new LinkedHashSet<>(roles), permissions, menuIds);
    }
}
