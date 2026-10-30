package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminTenantInitRequest;
import com.acme.platform.system.api.dto.AdminTenantInitView;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 租户开通初始化用例（tenant-management-complete）：建租户 + 租户管理员角色（默认授权）+ 初始管理员
 * + 角色绑定，一个事务内完成（10.2.1 仅覆盖本服务库；无远程调用 M-05——管理员初始密码不经本域，
 * 凭据归 platform-auth，开通后经凭据端点设置，见 AdminTenantInitRequest 注）。
 *
 * <p>租户归属写入口径：方法入口（事务内任何持久化访问前）将 TenantContext 绑定到新租户——
 * 事务级 Hibernate 会话以新租户创建，@TenantId 注入与过滤随之生效（不使用 @IgnoreTenant 跨租户写，
 * 12.4.2）；作用域结束恢复操作者上下文。平台目录表（租户/权限点/菜单）不受租户过滤影响。</p>
 *
 * <p>默认授权边界：租户管理员获得除平台保留域外的全部权限点与菜单——保留
 * {@code system:tenant:*}（租户目录管理）与 {@code infra:audit:*}（平台审计）。</p>
 */
@Service
public class TenantInitUseCase {

    /** 租户管理员默认角色编码（开通即建，非内置可调整）。 */
    public static final String TENANT_ADMIN_ROLE = "tenant_admin";

    /** 平台保留权限前缀（不授予租户管理员）。 */
    static final Set<String> RESERVED_PERMISSION_PREFIXES = Set.of("system:tenant:", "infra:audit:");

    private static final Logger log = LoggerFactory.getLogger(TenantInitUseCase.class);

    private final SysTenantRepository tenantRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysMenuRepository menuRepository;
    private final SysRoleMenuRepository roleMenuRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;

    public TenantInitUseCase(SysTenantRepository tenantRepository, SysRoleRepository roleRepository,
                             SysPermissionRepository permissionRepository,
                             SysRolePermissionRepository rolePermissionRepository,
                             SysMenuRepository menuRepository, SysRoleMenuRepository roleMenuRepository,
                             SysUserRepository userRepository, SysUserRoleRepository userRoleRepository) {
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.menuRepository = menuRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** 开通租户（含默认授权与初始管理员）；编码重复 / 用户名全局重复显式失败。 */
    @Transactional
    public AdminTenantInitView initialize(AdminTenantInitRequest request) {
        // 入口先绑定目标租户：事务级会话随后创建，全部租户归属写入按新租户（类注口径）
        TenantContext previous = TenantContext.get();
        TenantContext.bind(new TenantContext(request.tenantCode()));
        try {
            if (tenantRepository.existsByTenantCode(request.tenantCode())) {
                throw new BizException(SystemError.TENANT_CODE_DUPLICATED);
            }
            SysTenant tenant = tenantRepository
                    .save(new SysTenant(request.tenantCode(), request.tenantName(), request.remark()));
            SysRole adminRole = createTenantAdminRole();
            SysUser admin = createAdminUser(request, adminRole);
            log.info("租户开通初始化完成：tenant={} adminUserId={} operator={}",
                    request.tenantCode(), admin.getId(), previous == null ? null : previous.tenantId());
            return new AdminTenantInitView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                    admin.getId(), admin.getUsername(),
                    admin.getNickname());
        } catch (DataIntegrityViolationException ex) {
            // username 全局唯一约束兜底（前置跨租户查重不可靠，约束为唯一事实源）
            log.warn("租户开通撞唯一约束，按用户名重复收敛：tenantCode={}", request.tenantCode());
            throw new BizException(SystemError.USERNAME_DUPLICATED);
        } finally {
            TenantContext.bind(previous);
        }
    }

    private SysRole createTenantAdminRole() {
        SysRole role = roleRepository.save(
                new SysRole(TENANT_ADMIN_ROLE, "租户管理员", "开通初始化默认角色，可调整", false));
        rolePermissionRepository.saveAll(grantablePermissions().stream()
                .map(permission -> new SysRolePermission(role.getId(), permission.getId())).toList());
        roleMenuRepository.saveAll(grantableMenus().stream()
                .map(menu -> new SysRoleMenu(role.getId(), menu.getId())).toList());
        return role;
    }

    private SysUser createAdminUser(AdminTenantInitRequest request, SysRole adminRole) {
        SysUser admin = userRepository.save(
                new SysUser(request.adminUsername(), request.adminNickname(), null, null, false));
        userRoleRepository.save(new SysUserRole(admin.getId(), adminRole.getId()));
        return admin;
    }

    /** 可授予权限点：启用状态且不属平台保留域。 */
    private List<com.acme.platform.system.domain.SysPermission> grantablePermissions() {
        List<com.acme.platform.system.domain.SysPermission> grantable = new ArrayList<>();
        for (var permission : permissionRepository.findAll()) {
            if (permission.getStatus() == EnableStatus.ENABLED
                    && !isReserved(permission.getPermissionCode())) {
                grantable.add(permission);
            }
        }
        return grantable;
    }

    /** 可见菜单：权限码不属保留域，且目录类节点须保有可见后代（空目录不绑定，防悬空目录）。 */
    private List<SysMenu> grantableMenus() {
        List<SysMenu> all = menuRepository.findAll();
        Set<Long> kept = new LinkedHashSet<>();
        for (SysMenu menu : all) {
            String code = menu.getPermissionCode();
            if (code != null && !code.isBlank()) {
                if (!isReserved(code)) {
                    kept.add(menu.getId());
                }
            }
        }
        // 向上补全被保留菜单的祖先目录（含可见后代判定）
        for (SysMenu menu : all) {
            if (kept.contains(menu.getId())) {
                Long parentId = menu.getParentId();
                while (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && kept.add(parentId)) {
                    Long finalParentId = parentId;
                    parentId = all.stream()
                            .filter(candidate -> finalParentId.equals(candidate.getId()))
                            .findFirst().map(SysMenu::getParentId).orElse(null);
                }
            }
        }
        return all.stream().filter(menu -> kept.contains(menu.getId())).toList();
    }

    private static boolean isReserved(String permissionCode) {
        return RESERVED_PERMISSION_PREFIXES.stream().anyMatch(permissionCode::startsWith);
    }
}
