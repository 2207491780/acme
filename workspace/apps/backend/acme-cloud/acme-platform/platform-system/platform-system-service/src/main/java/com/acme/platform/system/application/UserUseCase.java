package com.acme.platform.system.application;

import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户管理用例（FR-006/007，架构设计 5.1 用户行）：事务边界在应用层、仅覆盖本服务库（10.2.1）；
 * 创建不含密码（凭据归 platform-auth，架构设计 4.6）；删除=软删 + 同事务解绑角色（引用约束 5.1 表）。
 */
@Service
public class UserUseCase {

    /** 默认内置超管用户名（种子固定，删除保护判定用；不硬编码账号数据，8.2.1 精神）。 */
    public static final String BUILT_IN_ADMIN_USERNAME = "admin";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;

    public UserUseCase(SysUserRepository userRepository, SysRoleRepository roleRepository,
                       SysUserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** 创建用户（无密码；username 全局唯一）。 */
    @Transactional
    public SysUser create(AdminUserCreate request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BizException(SystemError.USERNAME_DUPLICATED);
        }
        SysUser user = new SysUser(request.username(), request.nickname(), request.email(), request.phone(), false);
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

    /** 修改基本信息（乐观锁版本对照）。 */
    @Transactional
    public SysUser update(Long userId, AdminUserUpdate request) {
        SysUser user = get(userId);
        checkVersion(user, request.version());
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
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

    /** 分页查询（本租户内，租户自动过滤 12.4.1；username 模糊 / 状态过滤）。 */
    public PageResult<SysUser> page(PageQuery query, String username, EnableStatus status) {
        Specification<SysUser> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (username != null && !username.isBlank()) {
                predicates.add(cb.like(root.get("username"), "%" + username.trim() + "%"));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageRequest = PageRequest.of(query.page() - 1, query.size(),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<SysUser> page = userRepository.findAll(spec, pageRequest);
        return new PageResult<>(page.getContent(), page.getTotalElements());
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
}
