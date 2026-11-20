package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminTenantPackageCreate;
import com.acme.platform.system.api.dto.AdminTenantPackageDetailView;
import com.acme.platform.system.api.dto.AdminTenantPackageUpdate;
import com.acme.platform.system.api.dto.AdminTenantPackageView;
import com.acme.platform.system.domain.SysCapability;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysTenantPackage;
import com.acme.platform.system.domain.SysTenantPackageCapability;
import com.acme.platform.system.domain.SysTenantPackageMenu;
import com.acme.platform.system.infrastructure.repository.SysCapabilityRepository;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageCapabilityRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageMenuRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户套餐用例（REQ-20261121-022910）：套餐 CRUD + 菜单绑定全量替换 + 下发/分配范围解析。
 * REQ-20261121-054613：增能力绑定全量替换（capabilities 与 menuIds 同口径——创建/更新缺省=空集、
 * 未知能力码 → TENANT_CAPABILITY_INVALID、详情回传能力码全集）。
 * 参考模型（RuoYi-Vue-Plus/yudao）：套餐绑定菜单集 → 租户绑定套餐 → 下发时实时过滤
 * （确认②：无物化同步，套餐变更对存量绑定租户即时生效）。
 * 平台级目录表（sys_tenant_package / sys_tenant_package_menu / sys_capability /
 * sys_tenant_package_capability）无租户列、不过滤、无旁路需求；
 * 绑定表全量重绑走物理解绑旁路（uk(package_id,menu_id) / uk(package_id,capability_id) 决定，
 * sys_role_menu 同款口径）。
 */
@Service
public class TenantPackageUseCase {

    private final SysTenantPackageRepository packageRepository;
    private final SysTenantPackageMenuRepository packageMenuRepository;
    private final SysTenantRepository tenantRepository;
    private final SysMenuRepository menuRepository;
    private final SysCapabilityRepository capabilityRepository;
    private final SysTenantPackageCapabilityRepository packageCapabilityRepository;

    public TenantPackageUseCase(SysTenantPackageRepository packageRepository,
                                SysTenantPackageMenuRepository packageMenuRepository,
                                SysTenantRepository tenantRepository,
                                SysMenuRepository menuRepository,
                                SysCapabilityRepository capabilityRepository,
                                SysTenantPackageCapabilityRepository packageCapabilityRepository) {
        this.packageRepository = packageRepository;
        this.packageMenuRepository = packageMenuRepository;
        this.tenantRepository = tenantRepository;
        this.menuRepository = menuRepository;
        this.capabilityRepository = capabilityRepository;
        this.packageCapabilityRepository = packageCapabilityRepository;
    }

    // ---- CRUD（五端点承载，确认④）----

    /** 套餐分页（packageName 模糊；排序 id asc，契约 §API；menuCount/tenantCount 批量聚合免 N+1）。 */
    public PageResult<AdminTenantPackageView> page(PageQuery query, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.where(SysTenantPackage::getPackageName).like("%" + keyword.trim() + "%");
        }
        wrapper.orderBy(SysTenantPackage::getId, true);
        PageResult<SysTenantPackage> result = packageRepository.findAll(wrapper, query.page(), query.size());
        List<Long> ids = result.records().stream().map(SysTenantPackage::getId).toList();
        Map<Long, Long> menuCounts = menuCountsOf(ids);
        Map<Long, Long> tenantCounts = tenantCountsOf(ids);
        return new PageResult<>(result.records().stream()
                .map(pkg -> view(pkg, menuCounts.getOrDefault(pkg.getId(), 0L),
                        tenantCounts.getOrDefault(pkg.getId(), 0L)))
                .toList(), result.total());
    }

    /** 套餐详情（视图 + menuIds/capabilities 全集；不存在 → TENANT_PACKAGE_NOT_FOUND）。 */
    public AdminTenantPackageDetailView detail(Long packageId) {
        SysTenantPackage pkg = get(packageId);
        List<Long> menuIds = packageMenuIdsOf(packageId).stream().toList();
        return new AdminTenantPackageDetailView(pkg.getId(), pkg.getPackageName(), pkg.getRemark(),
                (long) menuIds.size(), tenantCountOf(packageId), pkg.getCreatedAt(), pkg.getVersion(),
                menuIds, packageCapabilityCodesOf(packageId));
    }

    /** 套餐创建（重名拒绝；menuIds/capabilities 存在性校验；绑定行随主行同事务落库）。 */
    @Transactional
    public AdminTenantPackageView create(AdminTenantPackageCreate request) {
        if (packageRepository.existsByPackageName(request.packageName())) {
            throw new BizException(SystemError.TENANT_PACKAGE_DUPLICATE);
        }
        Set<Long> menuIds = requireExistingMenus(request.menuIds());
        List<Long> capabilityIds = requireKnownCapabilityIds(request.capabilities());
        SysTenantPackage pkg = packageRepository.save(new SysTenantPackage(request.packageName(), request.remark()));
        replaceBindings(pkg.getId(), menuIds);
        replaceCapabilityBindings(pkg.getId(), capabilityIds);
        return view(pkg, menuIds.size(), 0L);
    }

    /** 套餐更新（同创建校验；menuIds/capabilities 全量替换绑定表；未绑定租户计数实时读取）。 */
    @Transactional
    public AdminTenantPackageView update(Long packageId, AdminTenantPackageUpdate request) {
        SysTenantPackage pkg = get(packageId);
        if (request.version() == null || !request.version().equals(pkg.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        if (packageRepository.existsByPackageNameAndIdNot(request.packageName(), packageId)) {
            throw new BizException(SystemError.TENANT_PACKAGE_DUPLICATE);
        }
        Set<Long> menuIds = requireExistingMenus(request.menuIds());
        List<Long> capabilityIds = requireKnownCapabilityIds(request.capabilities());
        pkg.setPackageName(request.packageName());
        pkg.setRemark(request.remark());
        packageRepository.save(pkg);
        replaceBindings(packageId, menuIds);
        replaceCapabilityBindings(packageId, capabilityIds);
        return view(pkg, menuIds.size(), tenantCountOf(packageId));
    }

    /**
     * 套餐删除（被任一租户绑定 → TENANT_PACKAGE_IN_USE 不删除；否则删绑定行 + 主行）：
     * 主行软删除（AuditBase 逻辑删除），菜单/能力绑定行均走物理解绑旁路（防软删行占唯一键，7.2.7 口径）。
     */
    @Transactional
    public void delete(Long packageId) {
        SysTenantPackage pkg = get(packageId);
        if (tenantRepository.existsByPackageId(packageId)) {
            throw new BizException(SystemError.TENANT_PACKAGE_IN_USE);
        }
        packageMenuRepository.deleteByPackageId(packageId);
        packageCapabilityRepository.deleteByPackageId(packageId);
        packageRepository.delete(pkg);
    }

    public SysTenantPackage get(Long packageId) {
        return packageRepository.findById(packageId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_PACKAGE_NOT_FOUND));
    }

    // ---- 菜单下发 / 分配范围（确认②⑤接线，MeUseCase / RoleUseCase 消费）----

    /**
     * 当前登录者所属租户绑定的套餐 ID（确认②）：
     * TenantContext 租户代码 → sys_tenant 平台目录直查（无租户列不过滤）；上下文缺失 /
     * 租户行缺失 / package_id NULL（=不受限）一律返回 null。
     */
    public Long currentTenantPackageId() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return null;
        }
        return tenantRepository.findByTenantCode(context.tenantId().trim())
                .map(SysTenant::getPackageId)
                .orElse(null);
    }

    /**
     * 菜单下发过滤（确认②语义终稿）：租户未绑定套餐 → 原样透传（行为与现状完全一致，AC-7 基线）；
     * 绑定套餐 → 角色授权菜单 ∩ 套餐菜单集，BUTTON 随所属叶子级联（叶在交集内则其 BUTTON 子节点
     * 随角色授权并入）；祖先链由视图组装（EntityViews.menuTree）按平台目录自动补全——叶在包内而
     * 目录不在时补目录，保证树完整渲染。读时实时过滤（不物化），套餐变更即时生效（AC-8）。
     */
    public Set<Long> dispatchMenuIds(Set<Long> roleMenuIds) {
        Long packageId = currentTenantPackageId();
        if (roleMenuIds == null || roleMenuIds.isEmpty() || packageId == null) {
            return roleMenuIds;
        }
        Set<Long> packageMenuIds = packageMenuIdsOf(packageId);
        Set<Long> visible = new LinkedHashSet<>(roleMenuIds);
        visible.retainAll(packageMenuIds);
        if (!visible.isEmpty()) {
            for (SysMenu menu : menuRepository.findAll()) {
                if (menu.getMenuType() == MenuType.BUTTON && visible.contains(menu.getParentId())
                        && roleMenuIds.contains(menu.getId())) {
                    visible.add(menu.getId());
                }
            }
        }
        return visible;
    }

    /**
     * 角色可分配菜单范围（确认⑤）：绑定套餐租户的角色-菜单分配范围=套餐菜单集；
     * BUTTON 随套餐内节点级联（叶在包内即可勾其按钮，与下发语义同范围）；
     * 祖先链并入（bindMenus 写入自动补父链，包外目录属下发展示集，允许绑定）。
     * 未绑定套餐返回 null=不受限（调用方跳过校验，行为与现状一致）。
     */
    public Set<Long> assignableMenuScope() {
        Long packageId = currentTenantPackageId();
        if (packageId == null) {
            return null;
        }
        Set<Long> scope = new LinkedHashSet<>(packageMenuIdsOf(packageId));
        List<SysMenu> all = menuRepository.findAll();
        Map<Long, SysMenu> byId = new HashMap<>();
        all.forEach(menu -> byId.put(menu.getId(), menu));
        for (SysMenu menu : all) {
            if (menu.getMenuType() == MenuType.BUTTON && scope.contains(menu.getParentId())) {
                scope.add(menu.getId());
            }
        }
        for (Long menuId : List.copyOf(scope)) {
            SysMenu menu = byId.get(menuId);
            Long parentId = menu == null ? null : menu.getParentId();
            while (parentId != null && parentId != SysMenu.ROOT_PARENT_ID && scope.add(parentId)) {
                SysMenu parent = byId.get(parentId);
                parentId = parent == null ? null : parent.getParentId();
            }
        }
        return scope;
    }

    /** menuIds 存在性校验（确认④：含不存在或已删菜单 → TENANT_PACKAGE_MENU_INVALID；逻辑删除自动过滤）。 */
    private Set<Long> requireExistingMenus(List<Long> menuIds) {
        Set<Long> distinct = new LinkedHashSet<>(menuIds == null ? List.of() : menuIds);
        if (distinct.isEmpty()) {
            return distinct;
        }
        List<SysMenu> menus = menuRepository.findAllById(distinct);
        if (menus.size() != distinct.size()) {
            throw new BizException(SystemError.TENANT_PACKAGE_MENU_INVALID);
        }
        return distinct;
    }

    /**
     * capabilities 校验 → 能力 id 序列（REQ-20261121-054613 确认④）：null/缺省=空集（全量替换口径）；
     * 空白项或目录启用集（status=ENABLED）之外的码 → TENANT_CAPABILITY_INVALID；重复码去重。
     */
    private List<Long> requireKnownCapabilityIds(List<String> capabilityCodes) {
        if (capabilityCodes == null || capabilityCodes.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : capabilityCodes) {
            if (code == null || code.isBlank()) {
                throw new BizException(SystemError.TENANT_CAPABILITY_INVALID);
            }
            distinct.add(code);
        }
        Map<String, Long> idByCode = capabilityRepository.findEnabledByCodeIn(distinct).stream()
                .collect(Collectors.toMap(SysCapability::getCapabilityCode, SysCapability::getId));
        if (idByCode.size() != distinct.size()) {
            throw new BizException(SystemError.TENANT_CAPABILITY_INVALID);
        }
        return distinct.stream().map(idByCode::get).toList();
    }

    /** 绑定表全量替换（物理解绑 + 批量落库，同事务；确认④"全量替换"语义）。 */
    private void replaceBindings(Long packageId, Set<Long> menuIds) {
        packageMenuRepository.deleteByPackageId(packageId);
        if (!menuIds.isEmpty()) {
            packageMenuRepository.saveAll(menuIds.stream()
                    .map(menuId -> new SysTenantPackageMenu(packageId, menuId)).toList());
        }
    }

    /** 能力绑定表全量替换（物理解绑 + 批量落库，同事务；REQ-20261121-054613 确认④，菜单同款口径）。 */
    private void replaceCapabilityBindings(Long packageId, List<Long> capabilityIds) {
        packageCapabilityRepository.deleteByPackageId(packageId);
        if (!capabilityIds.isEmpty()) {
            packageCapabilityRepository.saveAll(capabilityIds.stream()
                    .map(capabilityId -> new SysTenantPackageCapability(packageId, capabilityId)).toList());
        }
    }

    /** 套餐能力码全集（目录启用集内，findEnabledByIdIn 按 id asc 稳定输出；详情回传用）。 */
    private List<String> packageCapabilityCodesOf(Long packageId) {
        List<Long> capabilityIds = packageCapabilityRepository.findByPackageId(packageId).stream()
                .map(SysTenantPackageCapability::getCapabilityId)
                .toList();
        if (capabilityIds.isEmpty()) {
            return List.of();
        }
        return capabilityRepository.findEnabledByIdIn(capabilityIds).stream()
                .map(SysCapability::getCapabilityCode)
                .toList();
    }

    private Set<Long> packageMenuIdsOf(Long packageId) {
        return packageMenuRepository.findByPackageId(packageId).stream()
                .map(SysTenantPackageMenu::getMenuId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private long tenantCountOf(Long packageId) {
        return tenantRepository.findByPackageIdIn(List.of(packageId)).size();
    }

    /** 批量菜单绑定计数（分页 menuCount 聚合）。 */
    private Map<Long, Long> menuCountsOf(List<Long> packageIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (SysTenantPackageMenu binding : packageMenuRepository.findByPackageIdIn(packageIds)) {
            counts.merge(binding.getPackageId(), 1L, Long::sum);
        }
        return counts;
    }

    /** 批量租户绑定计数（分页 tenantCount 聚合）。 */
    private Map<Long, Long> tenantCountsOf(List<Long> packageIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (SysTenant tenant : tenantRepository.findByPackageIdIn(packageIds)) {
            if (tenant.getPackageId() != null) {
                counts.merge(tenant.getPackageId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private AdminTenantPackageView view(SysTenantPackage pkg, long menuCount, long tenantCount) {
        return new AdminTenantPackageView(pkg.getId(), pkg.getPackageName(), pkg.getRemark(),
                menuCount, tenantCount, pkg.getCreatedAt(), pkg.getVersion());
    }
}
