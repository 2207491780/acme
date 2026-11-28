package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysPositionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import com.acme.platform.system.security.DataScopeService;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理用例（FR-006/007，架构设计 5.1 用户行）：事务边界在应用层、仅覆盖本服务库（10.2.1）；
 * 创建不含密码（凭据归 platform-auth，架构设计 4.6）；删除=软删 + 同事务解绑角色（引用约束 5.1 表）。
 * REQ-20261114-120104：deptId / positionId 归属（校验存在）；列表 deptId 入参（含子部门）+
 * 数据范围五档过滤（示范域，AC-4——过滤条件见 DataScopeDecision 档位语义）。
 */
@Service
public class UserUseCase {

    /** 默认内置超管用户名（种子固定，删除保护判定用；不硬编码账号数据，8.2.1 精神）。 */
    public static final String BUILT_IN_ADMIN_USERNAME = "admin";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysDeptRepository deptRepository;
    private final SysPositionRepository positionRepository;
    private final DataScopeService dataScopeService;

    public UserUseCase(SysUserRepository userRepository, SysRoleRepository roleRepository,
                       SysUserRoleRepository userRoleRepository, SysDeptRepository deptRepository,
                       SysPositionRepository positionRepository, DataScopeService dataScopeService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.deptRepository = deptRepository;
        this.positionRepository = positionRepository;
        this.dataScopeService = dataScopeService;
    }

    /** 创建用户（无密码；username 租户域内唯一；deptId / positionId 非空时校验存在）。 */
    @Transactional
    public SysUser create(AdminUserCreate request) {
        // REQ-20261125-001738 确认①：查重按操作者当前租户域（TenantContext）——跨租户同名不再阻断
        if (userRepository.existsByUsername(currentTenantId(), request.username())) {
            throw new BizException(SystemError.USERNAME_DUPLICATED);
        }
        checkOrgRefs(request.deptId(), request.positionId());
        SysUser user = new SysUser(request.username(), request.nickname(), request.email(), request.phone(), false);
        user.setDeptId(request.deptId());
        user.setPositionId(request.positionId());
        user = userRepository.save(user);
        bindRolesInternal(user.getId(), request.roleIds());
        return user;
    }

    /** 用户详情（已删除 / 不存在 → 404）。 */
    public SysUser get(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BizException(SystemError.USER_NOT_FOUND));
    }

    /** 已绑定角色 ID 集。 */
    public List<Long> roleIdsOf(Long userId) {
        return userRoleRepository.findByUserId(userId).stream().map(SysUserRole::getRoleId).toList();
    }

    /** 用户列表角色批量装配（REQ-20261127-161433 P2-01）：一次 IN 查询分组返回，
     *  替代逐用户 roleIdsOf 的 N+1（单页 200 用户 201 次查询 → 常数次）。 */
    public Map<Long, List<Long>> roleIdsGroupedByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRoleRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));
    }

    /** 修改基本信息（乐观锁版本对照；deptId / positionId null=不调整，非空时校验存在）。 */
    @Transactional
    public SysUser update(Long userId, AdminUserUpdate request) {
        SysUser user = get(userId);
        checkVersion(user, request.version());
        checkOrgRefs(request.deptId(), request.positionId());
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        if (request.deptId() != null) {
            user.setDeptId(request.deptId());
        }
        if (request.positionId() != null) {
            user.setPositionId(request.positionId());
        }
        return userRepository.save(user);
    }

    /** 启用 / 停用（停用后新登录被拒；存量会话已知限制见架构设计第 12 章）。 */
    @Transactional
    public void updateStatus(Long userId, EnableStatus status) {
        SysUser user = get(userId);
        user.setStatus(status);
        userRepository.save(user);
    }

    /** 软删除 + 解绑角色（10.3.1 幂等：不存在 / 已删除视为成功）。 */
    @Transactional
    public void delete(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            if (Boolean.TRUE.equals(user.getBuiltIn()) || BUILT_IN_ADMIN_USERNAME.equals(user.getUsername())) {
                throw new BizException(SystemError.USER_DELETE_DENIED);
            }
            if (String.valueOf(user.getId()).equals(currentOperatorId())) {
                throw new BizException(SystemError.USER_DELETE_DENIED);
            }
            userRoleRepository.deleteByUserId(userId);
            userRepository.delete(user);
        });
    }

    /** 用户角色绑定整体替换（幂等，10.3.1）。 */
    @Transactional
    public void bindRoles(Long userId, List<Long> roleIds) {
        get(userId);
        bindRolesInternal(userId, roleIds);
    }

    /**
     * 分页查询（本租户内，租户自动过滤 12.4.1；username 模糊 / 状态过滤）。
     * REQ-20261114-120104 示范域（AC-4）：deptId 入参含子部门递归，叠加数据范围五档过滤——
     * ALL 档不过滤；DEPT/DEPT_AND_CHILD/CUSTOM 档按可见部门集过滤；SELF 档仅本人；
     * 多角色合并语义（并集）由 DataScopeService 解析承载。
     */
    public PageResult<SysUser> page(PageQuery query, String username, EnableStatus status, Long deptId) {
        // 数据范围解析（示范域 AC-4）：受限档且可见集为空 → 空页 fail-closed（不落 IN () 空条件）
        DataScopeDecision scope = dataScopeService.currentSubject();
        boolean restricted = !scope.unrestricted();
        Set<Long> scopeDeptIds = restricted ? scope.visibleDeptIds() : Set.of();
        Set<Long> scopeUserIds = restricted ? parseIds(scope.visibleUserIds()) : Set.of();
        if (restricted && scopeDeptIds.isEmpty() && scopeUserIds.isEmpty()) {
            return PageResult.empty();
        }
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (username != null && !username.isBlank()) {
            wrapper.where(SysUser::getUsername).like("%" + username.trim() + "%");
            hasCondition = true;
        }
        if (status != null) {
            if (hasCondition) {
                wrapper.and(SysUser::getStatus).eq(status);
            } else {
                wrapper.where(SysUser::getStatus).eq(status);
            }
        }
        // deptId 入参（含子部门）：目标部门不存在 / 跨租户 → 子树为空集，直接空页（不渲染 IN () 空条件）
        if (deptId != null) {
            List<Long> subtree = deptRepository.findSubtreeIds(deptId);
            if (subtree.isEmpty()) {
                return PageResult.empty();
            }
            if (hasCondition) {
                wrapper.and(SysUser::getDeptId).in(subtree);
            } else {
                wrapper.where(SysUser::getDeptId).in(subtree);
            }
            hasCondition = true;
        }
        if (restricted) {
            java.util.function.Consumer<QueryWrapper> scopeGroup = sub -> {
                if (scopeDeptIds.isEmpty()) {
                    sub.where(SysUser::getId).in(scopeUserIds);
                } else if (scopeUserIds.isEmpty()) {
                    sub.where(SysUser::getDeptId).in(scopeDeptIds);
                } else {
                    // 多角色合并：部门集 ∪ 本人集，括号分组 OR（与外部条件 AND 组合语义正确）
                    sub.where(SysUser::getDeptId).in(scopeDeptIds).or(SysUser::getId).in(scopeUserIds);
                }
            };
            if (hasCondition) {
                wrapper.and(scopeGroup);
            } else {
                wrapper.where(scopeGroup);
            }
        }
        wrapper.orderBy(SysUser::getId, false);
        return userRepository.findAll(wrapper, query.page(), query.size());
    }

    private static Set<Long> parseIds(Set<String> raw) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                ids.add(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                // 非数值主体 id 不属于用户主键域，忽略（审计域 operator 形态兼容）
            }
        }
        return ids;
    }

    /** 部门 / 岗位引用校验（非空即校验存在；跨租户 id 经租户过滤天然不存在）。 */
    private void checkOrgRefs(Long deptId, Long positionId) {
        if (deptId != null && deptRepository.findById(deptId).isEmpty()) {
            throw new BizException(SystemError.DEPT_NOT_FOUND);
        }
        if (positionId != null && positionRepository.findById(positionId).isEmpty()) {
            throw new BizException(SystemError.POSITION_NOT_FOUND);
        }
    }

    /** 绑定替换内部实现（校验角色存在后物理替换绑定行，7.2.7；批量写入一次落库）。 */
    private void bindRolesInternal(Long userId, List<Long> roleIds) {
        Set<Long> distinct = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds);
        if (!distinct.isEmpty()) {
            long found = roleRepository.findAllById(distinct).size();
            if (found != distinct.size()) {
                throw new BizException(SystemError.ROLE_NOT_FOUND);
            }
        }
        userRoleRepository.deleteByUserId(userId);
        userRoleRepository.saveAll(distinct.stream().map(roleId -> new SysUserRole(userId, roleId)).toList());
    }

    /** 乐观锁版本对照（请求版本与当前实体不一致 → 409 CONCURRENCY_CONFLICT）。 */
    private static void checkVersion(SysUser user, Long requestVersion) {
        if (requestVersion == null || !requestVersion.equals(user.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
    }

    private static String currentOperatorId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }

    /** 操作者当前租户（数据域唯一事实源 TenantContext；缺失时仓储侧 fail-closed）。 */
    private static String currentTenantId() {
        TenantContext context = TenantContext.get();
        return context == null ? null : context.tenantId();
    }
}
