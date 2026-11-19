package com.acme.platform.system.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminMeUpdate;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.security.PermissionDecisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前登录者视图用例（FR-008 权限声明与菜单可见性）：权限声明经令牌（roles claims）与上下文链路解析，
 * 菜单按角色绑定过滤（菜单级权限）；视图组装在接口层（11.5.3）。
 * REQ-20261117-025200：切换会话（仅内置 super_admin 可达，确认记录④）身份跨租户解析——
 * user 行 PK 全局直读、角色/菜单解析内置角色跨租户回退（详见 loadMe 注）。
 * REQ-20261119-082531：增自助资料修改（确认记录①）——身份解析复用 loadMe 链路，
 * 行更新经 updateSelfProfile 旁路（切换会话下本人行归属源租户）。
 */
@Service
public class MeUseCase {

    /** 当前登录者快照（user=本租户用户主数据；roles=令牌 claims；menuIds=可见菜单并集；
     *  deptName=本人部门名，REQ-20261117-180000 水印 {deptName} 供给，未分配/缺失=null）。 */
    public record MeSnapshot(SysUser user, Set<String> roles, Set<String> permissions, Set<Long> menuIds,
                             String deptName) {
    }

    private final SysUserRepository userRepository;
    private final SysDeptRepository deptRepository;
    private final MenuUseCase menuUseCase;
    private final PermissionDecisionService decisionService;

    public MeUseCase(SysUserRepository userRepository,
                     SysDeptRepository deptRepository, MenuUseCase menuUseCase,
                     PermissionDecisionService decisionService) {
        this.userRepository = userRepository;
        this.deptRepository = deptRepository;
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
        UserContext context = requireContext();
        Long userId = Long.valueOf(context.userId());
        SysUser user = userRepository.findByIdGlobal(userId)
                .orElseThrow(() -> new BizException(SystemError.USER_NOT_FOUND));
        return loadMeOf(user, context);
    }

    /**
     * 部门名解析（REQ-20261117-180000 水印 {deptName}）：deptId 来自已全局直读的 user 行，
     * 经 findByIdGlobal 旁路取部门名（切换会话下部门归属源租户，与 user 行同款口径）；
     * 未分配部门 / 部门行缺失 → null（前端渲染为空串）。
     */
    private String deptNameOf(SysUser user) {
        if (user.getDeptId() == null) {
            return null;
        }
        return deptRepository.findByIdGlobal(user.getDeptId())
                .map(com.acme.platform.system.domain.SysDept::getDeptName)
                .orElse(null);
    }

    /**
     * 本人资料修改（REQ-20261119-082531 确认记录①）：nickname 必填 ≤32 / email 可空 email 格式 /
     * phone 可空 1\d{10} 由接口层 @Valid 承载（AdminMeUpdate）；avatarUrl 可空，值域
     * http(s) 前缀或相对地址（相对地址以 {@code /} 起始）+ 长度 ≤512（长度接口层 @Size，前缀此处校验，
     * 违规 PROFILE_INVALID——防 javascript:/data: 等危险 scheme 经自助面写入）。
     * 行解析复用 loadMe 链路（PK 全局直读），更新经 updateSelfProfile 旁路（同款访问点声明），
     * 返回更新后快照（接口层组装 AdminMeView，前端刷新 meStore）。
     */
    @Transactional
    public MeSnapshot updateProfile(AdminMeUpdate request) {
        UserContext context = requireContext();
        Long userId = Long.valueOf(context.userId());
        SysUser user = userRepository.findByIdGlobal(userId)
                .orElseThrow(() -> new BizException(SystemError.USER_NOT_FOUND));
        validateAvatarUrl(request.avatarUrl());
        String avatarUrl = request.avatarUrl() == null || request.avatarUrl().isBlank()
                ? null : request.avatarUrl().trim();
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setAvatarUrl(avatarUrl);
        user = userRepository.updateSelfProfile(user);
        return loadMeOf(user, context);
    }

    /** avatarUrl 值域校验（确认记录①/TL 决策①）：http(s) 绝对地址或 {@code /} 起始相对地址；空值放行。 */
    private static void validateAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }
        String value = avatarUrl.trim();
        boolean allowed = value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("/");
        if (!allowed) {
            throw new BizException(SystemError.PROFILE_INVALID);
        }
    }

    /** 上下文守卫（loadMe 同款口径）：未认证 / userId 缺失统一 PERMISSION_DENIED。 */
    private static UserContext requireContext() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new BizException(SystemError.PERMISSION_DENIED);
        }
        return context;
    }

    /** 已有 user 行的快照汇聚（updateProfile 复用 loadMe 的角色/菜单/部门链路，不重复读 user 行）。 */
    private MeSnapshot loadMeOf(SysUser user, UserContext context) {
        Set<String> roles = context.roles();
        Set<String> permissions = decisionService.permissionCodesOfRoles(roles);
        List<Long> roleIds = decisionService.enabledRoleIdsOf(roles);
        Set<Long> menuIds;
        if (!roleIds.isEmpty()) {
            // 本租户命中：绑定行同租户读取（既有口径不变）
            menuIds = menuUseCase.visibleMenuIdsOf(roleIds);
        } else {
            // 切换会话回退（REQ-20261117-025200，确认记录④）：仅 claims 中命中内置角色行的编码
            // 取其跨租户行（findBuiltInByRoleCodeIn 旁路+审计），非内置角色码一律丢弃（防提权不回退）
            List<Long> builtInRoleIds = decisionService.builtInRoleIdsOf(roles);
            menuIds = menuUseCase.visibleMenuIdsOfBuiltIn(builtInRoleIds);
        }
        return new MeSnapshot(user, new LinkedHashSet<>(roles), permissions, menuIds, deptNameOf(user));
    }
}
