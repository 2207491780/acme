package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleUpdate;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
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
 * 角色与绑定用例（FR-006/007/008，架构设计 5.1 角色行与绑定关系管理）：
 * 权限点 / 菜单绑定整体替换幂等（10.3.1）；菜单绑定自动补全父链保证树完整（openapi AdminMenuIds 契约）。
 * 角色支持父子关系树状定义：根哨兵 0（sys_menu 同款）、父角色可调整（内置角色除外）、
 * 写入沿父链防环、存在子角色拒绝删除；树组装在接口层 EntityViews.roleTree。
 */
@Service
public class RoleUseCase {

    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysRoleMenuRepository roleMenuRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysMenuRepository menuRepository;

    public RoleUseCase(SysRoleRepository roleRepository, SysUserRoleRepository userRoleRepository,
                       SysRolePermissionRepository rolePermissionRepository,
                       SysRoleMenuRepository roleMenuRepository,
                       SysPermissionRepository permissionRepository, SysMenuRepository menuRepository) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.permissionRepository = permissionRepository;
        this.menuRepository = menuRepository;
    }

    /** 创建角色（(tenant_id, role_code) 唯一；parentId 缺省为根角色，父角色须存在）。 */
    @Transactional
    public SysRole create(AdminRoleCreate request) {
        if (roleRepository.existsByRoleCode(request.roleCode())) {
            throw new BizException(SystemError.ROLE_CODE_DUPLICATED);
        }
        Long parentId = request.parentId() == null ? SysRole.ROOT_PARENT_ID : request.parentId();
        if (parentId != SysRole.ROOT_PARENT_ID) {
            requireRole(parentId);
        }
        return roleRepository.save(new SysRole(parentId, request.roleCode(), request.roleName(),
                request.remark(), false));
    }

    public SysRole get(Long roleId) {
        return roleRepository.findById(roleId).orElseThrow(() -> new BizException(SystemError.ROLE_NOT_FOUND));
    }

    @Transactional
    public SysRole update(Long roleId, AdminRoleUpdate request) {
        SysRole role = get(roleId);
        if (request.version() == null || !request.version().equals(role.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        applyParent(role, request.parentId());
        role.setRoleName(request.roleName());
        role.setRemark(request.remark());
        return roleRepository.save(role);
    }

    /**
     * 调整父角色（null=不调整；0=移为根角色；>0=改父）：内置角色拒绝；父角色须存在；
     * 沿新父向上游走 parent 链校验目标父不得为自身或其子孙（visited 集防脏数据成环时死循环）。
     */
    private void applyParent(SysRole role, Long targetParentId) {
        if (targetParentId == null || targetParentId.equals(role.getParentId())) {
            return;
        }
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new BizException(SystemError.ROLE_REPARENT_DENIED);
        }
        Long cursor = targetParentId;
        Set<Long> visited = new LinkedHashSet<>();
        while (cursor != null && cursor != SysRole.ROOT_PARENT_ID && visited.add(cursor)) {
            if (cursor.equals(role.getId())) {
                throw new BizException(SystemError.ROLE_PARENT_CYCLE);
            }
            cursor = requireRole(cursor).getParentId();
        }
        role.setParentId(targetParentId);
    }

    @Transactional
    public void updateStatus(Long roleId, EnableStatus status) {
        SysRole role = get(roleId);
        role.setStatus(status);
        roleRepository.save(role);
    }

    /** 软删除 + 解绑权限点 / 菜单（内置角色 / 存在子角色 / 存在用户绑定拒绝；幂等）。 */
    @Transactional
    public void delete(Long roleId) {
        roleRepository.findById(roleId).ifPresent(role -> {
            if (Boolean.TRUE.equals(role.getBuiltIn())) {
                throw new BizException(SystemError.ROLE_DELETE_DENIED);
            }
            if (roleRepository.existsByParentId(roleId)) {
                throw new BizException(SystemError.ROLE_DELETE_DENIED);
            }
            if (userRoleRepository.existsByRoleId(roleId)) {
                throw new BizException(SystemError.ROLE_DELETE_DENIED);
            }
            rolePermissionRepository.deleteByRoleId(roleId);
            roleMenuRepository.deleteByRoleId(roleId);
            roleRepository.delete(role);
        });
    }

    /** 租户内全量角色（按 id 升序；树组装在接口层 EntityViews.roleTree）。 */
    public List<SysRole> listAll() {
        return roleRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** 按 ID 取角色，不存在抛 ROLE_NOT_FOUND。 */
    private SysRole requireRole(Long roleId) {
        return roleRepository.findById(roleId).orElseThrow(() -> new BizException(SystemError.ROLE_NOT_FOUND));
    }

    /** 分页查询（编码 / 名称模糊）。 */
    public PageResult<SysRole> page(PageQuery query, String keyword) {
        Specification<SysRole> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(cb.like(root.get("roleCode"), like), cb.like(root.get("roleName"), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<SysRole> page = roleRepository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), Sort.by(Sort.Direction.DESC, "id")));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    /** 角色绑定的权限点 ID 集。 */
    public List<Long> permissionIdsOf(Long roleId) {
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(SysRolePermission::getPermissionId).toList();
    }

    /** 角色绑定的菜单 ID 集。 */
    public List<Long> menuIdsOf(Long roleId) {
        return roleMenuRepository.findByRoleId(roleId).stream().map(SysRoleMenu::getMenuId).toList();
    }

    /** 权限点绑定整体替换（角色须存在；权限点全集校验存在）。 */
    @Transactional
    public void bindPermissions(Long roleId, List<Long> permissionIds) {
        get(roleId);
        Set<Long> distinct = new LinkedHashSet<>(permissionIds == null ? List.of() : permissionIds);
        if (!distinct.isEmpty() && permissionRepository.findAllById(distinct).size() != distinct.size()) {
            throw new BizException(SystemError.PERMISSION_NOT_FOUND);
        }
        replaceRolePermissions(roleId, distinct);
    }

    /** 菜单绑定整体替换（自动补全父链；缺失菜单 → 404）。 */
    @Transactional
    public void bindMenus(Long roleId, List<Long> menuIds) {
        get(roleId);
        Set<Long> requested = new LinkedHashSet<>(menuIds == null ? List.of() : menuIds);
        List<SysMenu> all = menuRepository.findAllById(requested);
        if (all.size() != requested.size()) {
            throw new BizException(SystemError.MENU_NOT_FOUND);
        }
        Set<Long> closure = withAncestors(requested);
        roleMenuRepository.deleteByRoleId(roleId);
        roleMenuRepository.saveAll(closure.stream().map(menuId -> new SysRoleMenu(roleId, menuId)).toList());
    }

    /** 绑定行整体替换（批量写入，一次落库）。 */
    private void replaceRolePermissions(Long roleId, Set<Long> permissionIds) {
        rolePermissionRepository.deleteByRoleId(roleId);
        rolePermissionRepository.saveAll(
                permissionIds.stream().map(permissionId -> new SysRolePermission(roleId, permissionId)).toList());
    }

    /**
     * 补全父链：按 ID 逐层向上收集，仅纳入实际存在的父节点（父节点缺失属数据损坏，按存在集合收敛，
     * 不扩散异常）；环状父链经闭包去重自然终止。
     */
    private Set<Long> withAncestors(Set<Long> menuIds) {
        Set<Long> closure = new LinkedHashSet<>();
        Set<Long> pending = new LinkedHashSet<>(menuIds);
        while (!pending.isEmpty()) {
            Set<Long> next = new LinkedHashSet<>();
            for (SysMenu menu : menuRepository.findAllById(pending)) {
                if (closure.add(menu.getId())) {
                    Long parentId = menu.getParentId();
                    if (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && !closure.contains(parentId)) {
                        next.add(parentId);
                    }
                }
            }
            pending = next;
        }
        return closure;
    }
}
