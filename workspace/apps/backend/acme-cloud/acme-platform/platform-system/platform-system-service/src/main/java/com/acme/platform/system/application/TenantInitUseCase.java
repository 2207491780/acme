package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminTenantInitRequest;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateContent;
import com.acme.platform.system.api.dto.AdminTenantInitView;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysTenantInitTemplate;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysConfigRepository;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantInitTemplateRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户开通初始化用例（tenant-management-complete）：建租户 + 租户管理员角色（默认授权）+ 初始管理员
 * + 角色绑定，一个事务内完成（10.2.1 仅覆盖本服务库；无远程调用 M-05——管理员初始密码不经本域，
 * 凭据归 platform-auth，开通后经凭据端点设置，见 AdminTenantInitRequest 注）。
 *
 * <p>租户归属写入口径：方法入口（事务内任何持久化访问前）将 TenantContext 绑定到新租户——
 * 事务内数据操作经 MyBatis-Flex 租户工厂按新租户解析，@TenantId 注入与过滤随之生效（不使用 @IgnoreTenant 跨租户写，
 * 12.4.2）；作用域结束恢复操作者上下文。平台目录表（租户/权限点/菜单/开通模板）不受租户过滤影响。</p>
 *
 * <p>默认授权边界：租户管理员获得除平台保留域外的全部权限点与菜单——保留
 * {@code system:tenant:*}（租户目录管理+运营总览）、{@code infra:audit:*}（平台审计）、
 * {@code system:tenant-package:*}（套餐管理，平台运营域，REQ-20261121-022910 TL 裁决收紧）与
 * {@code system:tenant-template:*}（开通模板管理，平台运营域，REQ-20261122-233426——无模板路径
 * 授权面相对 V32 种子水位保持零增量，开通模板管理不授租户管理员）。</p>
 *
 * <p>开通模板（REQ-20261122-233426 确认③）：请求携 templateCode 且命中模板 → 按模板快照建角色集
 * （rolePresets 显式授权）+ 写 sys_config + 默认套餐，跳过动态全量授权集计算；用户名解析=模板前缀
 * 优先（前缀+租户编码，「adminUsername 优先模板前缀」——请求显式值仅无前缀时兜底）；昵称=请求
 * 显式值优先、模板昵称兜底。未传/未命中模板=现行为逐字不变（兼容零回归，裁决记录⑥）。
 * 开通时快照语义：模板后改不影响已开通租户。</p>
 */
@Service
public class TenantInitUseCase {

    /** 租户管理员默认角色编码（开通即建，非内置可调整）。 */
    public static final String TENANT_ADMIN_ROLE = "tenant_admin";

    /**
     * 平台保留权限前缀（不授予租户管理员）。REQ-20261121-022910 TL 裁决（偏离⑥收紧）：
     * {@code system:tenant-package:} 入保留域——套餐管理为平台运营域，开通默认授权
     * （grantablePermissions/grantableMenus）与非内置角色授权面守卫（RoleUseCase 保留域引用）联动排除。
     * REQ-20261122-233426：{@code system:tenant-template:}（开通模板管理）同理入保留域——
     * 平台运营域成员，开通默认授权与非内置角色授权面守卫联动排除（V32 种子水位下无模板路径
     * 授权面零增量，确认③「未传=现行为逐字不变」的强口径承载）。
     */
    static final Set<String> RESERVED_PERMISSION_PREFIXES = Set.of("system:tenant:", "infra:audit:",
            "system:tenant-package:", "system:tenant-template:");

    private static final Logger log = LoggerFactory.getLogger(TenantInitUseCase.class);

    /** 初始管理员用户名上限（sys_user.username varchar(64)，V1 口径）。 */
    private static final int USERNAME_MAX_LENGTH = 64;

    private final SysTenantRepository tenantRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysMenuRepository menuRepository;
    private final SysRoleMenuRepository roleMenuRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysTenantInitTemplateRepository templateRepository;
    private final SysConfigRepository configRepository;
    private final TenantInitTemplateUseCase tenantInitTemplateUseCase;

    /**
     * 全量构造（Spring 装配）：模板/配置/模板校验协作者齐备——开通模板路径可用。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TenantInitUseCase(SysTenantRepository tenantRepository, SysRoleRepository roleRepository,
                             SysPermissionRepository permissionRepository,
                             SysRolePermissionRepository rolePermissionRepository,
                             SysMenuRepository menuRepository, SysRoleMenuRepository roleMenuRepository,
                             SysUserRepository userRepository, SysUserRoleRepository userRoleRepository,
                             SysTenantInitTemplateRepository templateRepository,
                             SysConfigRepository configRepository,
                             TenantInitTemplateUseCase tenantInitTemplateUseCase) {
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.menuRepository = menuRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.templateRepository = templateRepository;
        this.configRepository = configRepository;
        this.tenantInitTemplateUseCase = tenantInitTemplateUseCase;
    }

    /** 兼容构造（REQ-20261122-233426 之前直构调用面零改动，TenantInitUseCaseTest 先例口径）：
     *  模板/配置协作者缺席=null——仅动态全量授权路径可达（模板命中判定对 null 仓储直落未命中）。 */
    public TenantInitUseCase(SysTenantRepository tenantRepository, SysRoleRepository roleRepository,
                             SysPermissionRepository permissionRepository,
                             SysRolePermissionRepository rolePermissionRepository,
                             SysMenuRepository menuRepository, SysRoleMenuRepository roleMenuRepository,
                             SysUserRepository userRepository, SysUserRoleRepository userRoleRepository) {
        this(tenantRepository, roleRepository, permissionRepository, rolePermissionRepository,
                menuRepository, roleMenuRepository, userRepository, userRoleRepository,
                null, null, null);
    }

    /**
     * 开通租户（含默认授权与初始管理员）；编码重复 / 用户名域内重复显式失败
     * （REQ-20261125-001738 确认④：跨租户同名不再阻断开通）。
     * REQ-20261122-233426 确认③：templateCode 命中模板时按模板快照建角色集/写配置/默认套餐，
     * 跳过动态全量授权集计算；未传/未命中=现行为逐字不变（兼容零回归，裁决记录⑥）。
     */
    @Transactional
    public AdminTenantInitView initialize(AdminTenantInitRequest request) {
        // 模板命中判定与内容解析在绑定前（平台级模板表无租户过滤面）；管理兜底校验在先，
        // 对齐原 bean 校验「参数失败先于业务失败」次序（adminUsername/adminNickname 放宽可空后
        // 由用例层以同码 400 收敛）
        SysTenantInitTemplate template = resolveTemplate(request.templateCode());
        AdminTenantInitTemplateContent content = template == null ? null
                : tenantInitTemplateUseCase.validateAndNormalize(
                        tenantInitTemplateUseCase.deserialize(template.getContent()));
        String adminUsername = effectiveAdminUsername(request, content);
        String adminNickname = effectiveAdminNickname(request, content);
        // 入口先绑定目标租户：事务级会话随后创建，全部租户归属写入按新租户（类注口径）
        TenantContext previous = TenantContext.get();
        TenantContext.bind(new TenantContext(request.tenantCode()));
        try {
            if (tenantRepository.existsByTenantCode(request.tenantCode())) {
                throw new BizException(SystemError.TENANT_CODE_DUPLICATED);
            }
            // REQ-20261125-001738 确认④：冲突校验放宽为域内——仅目标租户内同名阻断，跨租户同名放行
            if (userRepository.existsByUsername(request.tenantCode(), adminUsername)) {
                throw new BizException(SystemError.USERNAME_DUPLICATED);
            }
            SysTenant tenant = new SysTenant(request.tenantCode(), request.tenantName(), request.remark());
            applyParent(tenant, request.parentId());
            if (content != null) {
                // 确认③：默认套餐随模板快照应用（开通前存在性由模板保存校验，快照语义不再复核）
                tenant.setPackageId(template.getDefaultPackageId());
            }
            tenantRepository.save(tenant);
            SysRole adminRole;
            if (content == null) {
                adminRole = createTenantAdminRole();
            } else {
                // 确认③：按模板快照建角色集并绑定授权（跳过动态全量授权集计算）
                adminRole = createRolesFromTemplate(template.getCode(), content);
                applyConfigEntries(content);
            }
            SysUser admin = createAdminUser(adminUsername, adminNickname, adminRole);
            log.info("租户开通初始化完成：tenant={} adminUserId={} operator={}",
                    request.tenantCode(), admin.getId(), previous == null ? null : previous.tenantId());
            return new AdminTenantInitView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                    admin.getId(), admin.getUsername(),
                    admin.getNickname());
        } catch (DataIntegrityViolationException ex) {
            // username 域内唯一约束兜底（前置同域查重不可靠，约束为唯一事实源）
            log.warn("租户开通撞唯一约束，按用户名重复收敛：tenantCode={}", request.tenantCode());
            throw new BizException(SystemError.USERNAME_DUPLICATED);
        } finally {
            TenantContext.bind(previous);
        }
    }

    /**
     * 模板命中判定（确认③：未传/未命中=现行为逐字不变——编码缺失/空白/查无此码一律返回 null，
     * 走动态全量授权既有路径，不显式报错）。平台级模板表无租户过滤面，绑定前直查。
     */
    private SysTenantInitTemplate resolveTemplate(String templateCode) {
        if (templateRepository == null || templateCode == null || templateCode.isBlank()) {
            return null;
        }
        return templateRepository.findByCode(templateCode.trim()).orElse(null);
    }

    /**
     * 初始管理员用户名解析（确认③「adminUsername 优先模板前缀」——模板命中且有前缀时
     * 前缀优先于请求显式值，测试面 REQ20261122233426 同口径）：命中模板且有前缀 →
     * 前缀+租户编码（跨租户可读的域内唯一合成，REQ-20261125-001738 确认④保留；
     * 越界 sys_user.username varchar(64) 显式失败）；
     * 无前缀时请求显式值兜底；仍缺失 → VALIDATION_FAILED（400，与原 bean 校验同码同态）。
     */
    private String effectiveAdminUsername(AdminTenantInitRequest request,
                                          AdminTenantInitTemplateContent content) {
        if (content != null && content.adminUsernamePrefix() != null
                && !content.adminUsernamePrefix().isBlank()) {
            String composite = content.adminUsernamePrefix().trim() + request.tenantCode();
            if (composite.length() > USERNAME_MAX_LENGTH) {
                throw new BizException(WebError.VALIDATION_FAILED);
            }
            return composite;
        }
        String explicit = request.adminUsername();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        throw new BizException(WebError.VALIDATION_FAILED);
    }

    /** 初始管理员昵称解析（契约未定优先级——请求显式值优先，模板昵称兜底，仍缺失 VALIDATION_FAILED）。 */
    private String effectiveAdminNickname(AdminTenantInitRequest request,
                                          AdminTenantInitTemplateContent content) {
        String explicit = request.adminNickname();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (content != null && content.adminNickname() != null && !content.adminNickname().isBlank()) {
            return content.adminNickname().trim();
        }
        throw new BizException(WebError.VALIDATION_FAILED);
    }

    /**
     * 开通挂父（REQ-20261122-102954 确认⑤：平台侧建子租户走本模板，初始管理员随 TenantContext
     * 绑定归子租户编码）：parent 须存在（TENANT_NOT_FOUND）；level=父+1，深度超限
     * TENANT_DEPTH_EXCEEDED（上限 {@link TenantUseCase#MAX_TENANT_DEPTH}=3）。新租户无后代，无环风险。
     */
    private void applyParent(SysTenant tenant, Long parentId) {
        if (parentId == null) {
            return;
        }
        SysTenant parent = tenantRepository.findById(parentId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        int childLevel = (parent.getLevel() == null ? 1 : parent.getLevel()) + 1;
        if (childLevel > TenantUseCase.MAX_TENANT_DEPTH) {
            throw new BizException(SystemError.TENANT_DEPTH_EXCEEDED);
        }
        tenant.setParentId(parent.getId());
        tenant.setLevel(childLevel);
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

    private SysUser createAdminUser(String adminUsername, String adminNickname, SysRole adminRole) {
        SysUser admin = userRepository.save(
                new SysUser(adminUsername, adminNickname, null, null, false));
        userRoleRepository.save(new SysUserRole(admin.getId(), adminRole.getId()));
        return admin;
    }

    /**
     * 按模板快照建角色集并绑定授权（REQ-20261122-233426 确认③）：逐 preset 创建租户内角色
     * （非内置；roleCode=preset.roleKey），绑定授权=permissionCodes 解析为启用中、非保留域权限点
     * （快照语义：模板保存后编码被删/停用/入保留域的项跳过并 WARN，不阻断开通）→ role_permission，
     * 菜单绑定=码 ∈ 预设集的菜单节点（BUTTON/叶子投影）+ 祖先目录补全（grantableMenus 同构、
     * 范围限定预设码）→ role_menu。跳过动态全量授权集计算（未入预设的权限/菜单不授）。
     *
     * @return 主角色（首个预设角色，初始管理员绑定之）
     */
    private SysRole createRolesFromTemplate(String templateCode, AdminTenantInitTemplateContent content) {
        Map<String, Long> permissionIdByCode = permissionRepository.findAll().stream()
                .filter(permission -> permission.getStatus() == EnableStatus.ENABLED
                        && !isReserved(permission.getPermissionCode()))
                .collect(Collectors.toMap(SysPermission::getPermissionCode, SysPermission::getId,
                        (first, second) -> first));
        List<SysMenu> allMenus = menuRepository.findAll();
        SysRole primaryRole = null;
        List<String> roleKeys = new ArrayList<>();
        for (AdminTenantInitTemplateContent.RolePreset preset : content.rolePresets()) {
            String roleKey = preset.roleKey().trim();
            SysRole role = roleRepository.save(new SysRole(roleKey, preset.roleName().trim(),
                    "开通模板初始化角色", false));
            roleKeys.add(roleKey);
            if (primaryRole == null) {
                primaryRole = role;
            }
            Set<String> codes = preset.permissionCodes().stream().map(String::trim)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Long> permissionIds = codes.stream()
                    .map(code -> resolvePermissionId(permissionIdByCode, code))
                    .filter(Objects::nonNull)
                    .toList();
            rolePermissionRepository.saveAll(permissionIds.stream()
                    .map(permissionId -> new SysRolePermission(role.getId(), permissionId)).toList());
            roleMenuRepository.saveAll(menusForPermissionCodes(allMenus, codes).stream()
                    .map(menu -> new SysRoleMenu(role.getId(), menu.getId())).toList());
        }
        log.info("按开通模板初始化角色集：template={} roles={}", templateCode, roleKeys);
        return primaryRole;
    }

    /** 预设权限码 → 权限点 id（快照语义：已删/停用/入保留域的码跳过 + WARN，不阻断开通）。 */
    private Long resolvePermissionId(Map<String, Long> permissionIdByCode, String code) {
        Long permissionId = permissionIdByCode.get(code);
        if (permissionId == null) {
            log.warn("开通模板权限码快照失效，跳过绑定：permissionCode={}", code);
        }
        return permissionId;
    }

    /**
     * 菜单绑定集（码 ∈ 预设集的菜单节点 + 祖先目录补全）：grantableMenus 同构投影逻辑、
     * 范围限定预设码——快照授权集的菜单承载（确认③「绑定授权」；不触碰 grantableMenus 既有面）。
     */
    private List<SysMenu> menusForPermissionCodes(List<SysMenu> all, Set<String> codes) {
        Map<Long, SysMenu> byId = all.stream()
                .collect(Collectors.toMap(SysMenu::getId, menu -> menu, (first, second) -> first));
        Set<Long> kept = new LinkedHashSet<>();
        for (SysMenu menu : all) {
            String code = menu.getPermissionCode();
            if (code != null && !code.isBlank() && codes.contains(code)) {
                kept.add(menu.getId());
            }
        }
        // 向上补全被保留菜单的祖先目录（id 索引直查父行，grantableMenus 同构）
        for (SysMenu menu : all) {
            if (kept.contains(menu.getId())) {
                Long parentId = menu.getParentId();
                while (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && kept.add(parentId)) {
                    SysMenu parent = byId.get(parentId);
                    parentId = parent == null ? null : parent.getParentId();
                }
            }
        }
        return all.stream().filter(menu -> kept.contains(menu.getId())).toList();
    }

    /**
     * 模板配置项写入 sys_config（确认②③「写配置」租户域）：TenantContext 已绑定新租户——
     * @TenantId 自动注入；平台缺省基座（水印开/验证码关/锁屏关/页脚不显/模板空，V15/V16/V17
     * 列缺省同源）上逐项覆盖；configEntries 空=不建行（无配置行租户按缺省生效，既有口径）。
     */
    private void applyConfigEntries(AdminTenantInitTemplateContent content) {
        if (content.configEntries().isEmpty()) {
            return;
        }
        SysConfig config = new SysConfig(true, false, 0, null, Boolean.FALSE, null);
        for (AdminTenantInitTemplateContent.ConfigEntry entry : content.configEntries()) {
            applyConfigEntry(config, entry.configKey().trim(), entry.value());
        }
        configRepository.save(config);
    }

    /** 配置项覆盖（保存时已校验可解析性；改库致解析失效兜底跳过 + WARN，不阻断开通）。
     *  键词汇引用 {@link TenantConfigKeys} 常量（P2-03 单一登记，与模板校验同源）。 */
    private void applyConfigEntry(SysConfig config, String configKey, String value) {
        try {
            switch (configKey) {
                case TenantConfigKeys.WATERMARK_ENABLED -> config.setWatermarkEnabled(Boolean.parseBoolean(value));
                case TenantConfigKeys.CAPTCHA_ENABLED -> config.setCaptchaEnabled(Boolean.parseBoolean(value));
                case TenantConfigKeys.LOCK_IDLE_TIMEOUT_MS -> config.setLockIdleTimeoutMs(
                        value == null ? 0 : Integer.valueOf(value.trim()));
                case TenantConfigKeys.FOOTER_COPYRIGHT -> config.setFooterCopyright(value);
                case TenantConfigKeys.FOOTER_VISIBLE -> config.setFooterVisible(Boolean.parseBoolean(value));
                case TenantConfigKeys.WATERMARK_TEMPLATE -> config.setWatermarkTemplate(value);
                default -> log.warn("开通模板未知配置键，跳过写入：configKey={}", configKey);
            }
        } catch (RuntimeException ex) {
            log.warn("开通模板配置值解析失效，跳过写入：configKey={} value={}", configKey, value, ex);
        }
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
        Map<Long, SysMenu> byId = all.stream()
                .collect(Collectors.toMap(SysMenu::getId, menu -> menu, (first, second) -> first));
        Set<Long> kept = new LinkedHashSet<>();
        for (SysMenu menu : all) {
            String code = menu.getPermissionCode();
            if (code != null && !code.isBlank()) {
                if (!isReserved(code)) {
                    kept.add(menu.getId());
                }
            }
        }
        // 向上补全被保留菜单的祖先目录（含可见后代判定）；id 索引直查父行（S14：免全表逐层扫描）
        for (SysMenu menu : all) {
            if (kept.contains(menu.getId())) {
                Long parentId = menu.getParentId();
                while (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && kept.add(parentId)) {
                    SysMenu parent = byId.get(parentId);
                    parentId = parent == null ? null : parent.getParentId();
                }
            }
        }
        return all.stream().filter(menu -> kept.contains(menu.getId())).toList();
    }

    private static boolean isReserved(String permissionCode) {
        return RESERVED_PERMISSION_PREFIXES.stream().anyMatch(permissionCode::startsWith);
    }
}
