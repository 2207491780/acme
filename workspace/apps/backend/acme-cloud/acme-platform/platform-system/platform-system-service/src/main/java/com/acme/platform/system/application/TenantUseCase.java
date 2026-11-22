package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.client.TenantSessionRevocationGateway;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 租户管理用例（FR-006 Q-004，架构设计 5.1 租户行）：平台管理跨租户场景显式声明并纳入审计（12.4.2）；
 * 无删除能力——停用代替（FR-007）；默认租户不可停用（种子保护）。
 * REQ-20261117-025200：增停用租户会话枚举撤销——事务提交后（afterCommit）经 system → auth
 * 撤销契约调用，失败降级 WARN 不回滚停用（DB 为事实源，残留会话至多存活至 access TTL 30min，
 * 与互踢撤销集失败降级同口径，确认记录①）；启用（ENABLED）不触发撤销；
 * 下拉排除已过期租户（确认记录③）；按代码直查目录（by-code 内部契约数据源）。
 * REQ-20261121-022910：create/update 支持 packageId（null=不绑定 / 解除绑定=不受限；
 * 非空须为既有套餐，否则 TENANT_PACKAGE_NOT_FOUND——逻辑校验无 FK，确认③④）。
 * REQ-20261122-102954：层级扩展——create/update 收 parentId（环检测 TENANT_CYCLE+深度校验
 * TENANT_DEPTH_EXCEEDED≤3+改父后代 level 递归重算）；updateStatus 停用级联后代（afterCommit
 * 逐租户撤会话；恢复父不自动恢复子）；tree 数据源（listAll，平台侧旁路）。
 */
@Service
public class TenantUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantUseCase.class);

    /** 默认租户编码（种子固定；停用保护判定）。 */
    public static final String DEFAULT_TENANT_CODE = "default";

    /** 租户层级深度上限（1=顶层，确认③：最大深度 3）。 */
    public static final int MAX_TENANT_DEPTH = 3;

    private final SysTenantRepository tenantRepository;
    private final TenantSessionRevocationGateway revocationGateway;
    private final SysTenantPackageRepository tenantPackageRepository;

    public TenantUseCase(SysTenantRepository tenantRepository, TenantSessionRevocationGateway revocationGateway,
                         SysTenantPackageRepository tenantPackageRepository) {
        this.tenantRepository = tenantRepository;
        this.revocationGateway = revocationGateway;
        this.tenantPackageRepository = tenantPackageRepository;
    }

    @Transactional
    public SysTenant create(AdminTenantCreate request) {
        if (tenantRepository.existsByTenantCode(request.tenantCode())) {
            throw new BizException(SystemError.TENANT_CODE_DUPLICATED);
        }
        requirePackageExists(request.packageId());
        SysTenant tenant = new SysTenant(request.tenantCode(), request.tenantName(), request.remark());
        tenant.setContactName(request.contactName());
        tenant.setContactPhone(request.contactPhone());
        tenant.setExpiresAt(request.expiresAt());
        tenant.setPackageId(request.packageId());
        applyParentOnCreate(tenant, request.parentId());
        return tenantRepository.save(tenant);
    }

    /** 租户详情（跨租户平台管理场景，显式旁路声明 + 审计 12.4.2）。 */
    @IgnoreTenant("tenant-detail-lookup")
    public SysTenant get(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
    }

    /**
     * 按租户代码查租户详情（REQ-20261117-025200 by-code 内部契约数据源，确认记录③）：
     * sys_tenant 平台目录表无租户列，直查；查不到抛 TENANT_NOT_FOUND（404，消费方按需映射）。
     */
    @IgnoreTenant("tenant-code-lookup")
    public SysTenant findByCode(String tenantCode) {
        return tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
    }

    /**
     * 启用租户清单（登录页下拉数据源，login-tenant-select；平台级跨租户查询，显式旁路声明）。
     * REQ-20261117-025200 确认记录③：排除已过期租户（expiresAt 非 null 且 &lt;= now）。
     */
    @IgnoreTenant("login-tenant-directory")
    public List<SysTenant> listEnabled() {
        QueryWrapper wrapper = QueryWrapper.create()
                .where(SysTenant::getStatus).eq(EnableStatus.ENABLED)
                .orderBy(SysTenant::getTenantCode, true);
        Instant now = Instant.now();
        return tenantRepository.findAll(wrapper).stream()
                .filter(tenant -> tenant.getExpiresAt() == null || tenant.getExpiresAt().isAfter(now))
                .toList();
    }

    @Transactional
    public SysTenant update(Long tenantId, AdminTenantUpdate request) {
        SysTenant tenant = get(tenantId);
        if (request.version() == null || !request.version().equals(tenant.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        requirePackageExists(request.packageId());
        tenant.setTenantName(request.tenantName());
        tenant.setRemark(request.remark());
        tenant.setContactName(request.contactName());
        tenant.setContactPhone(request.contactPhone());
        tenant.setExpiresAt(request.expiresAt());
        tenant.setPackageId(request.packageId());
        applyParentOnUpdate(tenant, request.parentId());
        return tenantRepository.save(tenant);
    }

    /**
     * 套餐绑定存在性校验（REQ-20261121-022910 确认④）：null=不绑定 / 解除绑定放行；
     * 非空须命中既有套餐行（软删行被逻辑删除过滤视同不存在），否则 TENANT_PACKAGE_NOT_FOUND。
     */
    private void requirePackageExists(Long packageId) {
        if (packageId != null && !tenantPackageRepository.existsById(packageId)) {
            throw new BizException(SystemError.TENANT_PACKAGE_NOT_FOUND);
        }
    }

    /**
     * 启用 / 停用（默认租户不可停用；停用成功事务提交后枚举撤销该租户活跃会话，确认记录①②；
     * 撤销调用失败仅 WARN——停用不回滚）。
     * REQ-20261122-102954 确认⑦：停用级联后代——沿 parent 链递归物化后代 status=DISABLED
     * （自身 status 即权威，解析零层级感知），afterCommit 逐租户撤销会话；
     * 恢复（ENABLED）不自动恢复子（显式逐个恢复）。
     */
    @Transactional
    public void updateStatus(Long tenantId, EnableStatus status) {
        SysTenant tenant = get(tenantId);
        if (status == EnableStatus.DISABLED && DEFAULT_TENANT_CODE.equals(tenant.getTenantCode())) {
            throw new BizException(SystemError.TENANT_DISABLE_DENIED);
        }
        tenant.setStatus(status);
        tenantRepository.save(tenant);
        if (status == EnableStatus.DISABLED) {
            List<String> revokedCodes = new ArrayList<>();
            revokedCodes.add(tenant.getTenantCode());
            disableDescendants(tenant.getId(), revokedCodes);
            registerAfterCommitRevocation(revokedCodes);
        }
    }

    /**
     * 全量租户目录（tree 端点数据源，REQ-20261122-102954 确认⑨；跨租户平台管理场景，
     * 显式旁路声明 + 审计 12.4.2）。
     */
    @IgnoreTenant("tenant-tree-lookup")
    public List<SysTenant> listAll() {
        return tenantRepository.findAll(QueryWrapper.create()
                .orderBy(SysTenant::getTenantCode, true));
    }

    // ---------- 层级（REQ-20261122-102954 确认②③） ----------

    /** 创建挂父：parent 须存在（TENANT_NOT_FOUND）；level=父+1，深度超限 TENANT_DEPTH_EXCEEDED。 */
    private void applyParentOnCreate(SysTenant tenant, Long parentId) {
        if (parentId == null) {
            return;
        }
        SysTenant parent = tenantRepository.findById(parentId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        int childLevel = requireParentLevel(parent) + 1;
        if (childLevel > MAX_TENANT_DEPTH) {
            throw new BizException(SystemError.TENANT_DEPTH_EXCEEDED);
        }
        tenant.setParentId(parent.getId());
        tenant.setLevel(childLevel);
    }

    /**
     * 改父（允许，确认③）：null=移至顶层（level=1）；非空须存在且不构成环（目标父为自身或其子孙
     * → TENANT_CYCLE，原数据不动）；深度=新父链最深后代整体重算后超限 → TENANT_DEPTH_EXCEEDED；
     * 通过后后代 level 递归重算（子=父+1）。
     */
    private void applyParentOnUpdate(SysTenant tenant, Long newParentId) {
        if (newParentId == null) {
            tenant.setParentId(null);
            tenant.setLevel(1);
            recalcDescendantLevels(tenant);
            return;
        }
        if (newParentId.equals(tenant.getId())) {
            throw new BizException(SystemError.TENANT_CYCLE);
        }
        SysTenant parent = tenantRepository.findById(newParentId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        rejectAncestorCycle(tenant.getId(), parent);
        int newLevel = requireParentLevel(parent) + 1;
        if (newLevel + descendantLevelShift(tenant) > MAX_TENANT_DEPTH) {
            throw new BizException(SystemError.TENANT_DEPTH_EXCEEDED);
        }
        tenant.setParentId(parent.getId());
        tenant.setLevel(newLevel);
        recalcDescendantLevels(tenant);
    }

    /** 父租户层级读取（防御存量 NULL 视为顶层 1）。 */
    private int requireParentLevel(SysTenant parent) {
        return parent.getLevel() == null ? 1 : parent.getLevel();
    }

    /** 环检测：沿新父向上走祖先链，命中被移动租户即成环（TENANT_CYCLE）。 */
    private void rejectAncestorCycle(Long movingId, SysTenant parent) {
        SysTenant cursor = parent;
        while (cursor != null) {
            if (cursor.getId().equals(movingId)) {
                throw new BizException(SystemError.TENANT_CYCLE);
            }
            cursor = cursor.getParentId() == null ? null
                    : tenantRepository.findById(cursor.getParentId()).orElse(null);
        }
    }

    /** 后代相对层级跨度（BFS 逐层经 idx(parent_id) 取直接子；无后代为 0）。 */
    private int descendantLevelShift(SysTenant tenant) {
        int ownLevel = requireParentLevel(tenant);
        int max = ownLevel;
        List<SysTenant> frontier = tenantRepository.findByParentId(tenant.getId());
        while (!frontier.isEmpty()) {
            List<SysTenant> next = new ArrayList<>();
            for (SysTenant node : frontier) {
                max = Math.max(max, requireParentLevel(node));
                next.addAll(tenantRepository.findByParentId(node.getId()));
            }
            frontier = next;
        }
        return max - ownLevel;
    }

    /** 后代 level 递归重算（子=父+1；调用前父 level 已就位）。 */
    private void recalcDescendantLevels(SysTenant parent) {
        for (SysTenant child : tenantRepository.findByParentId(parent.getId())) {
            child.setLevel(requireParentLevel(parent) + 1);
            tenantRepository.save(child);
            recalcDescendantLevels(child);
        }
    }

    /** 级联停用后代（BFS 物化 status=DISABLED，逐租户记入撤销集；自身 status 即权威，确认⑦）。 */
    private void disableDescendants(Long parentId, List<String> revokedCodes) {
        for (SysTenant child : tenantRepository.findByParentId(parentId)) {
            child.setStatus(EnableStatus.DISABLED);
            tenantRepository.save(child);
            revokedCodes.add(child.getTenantCode());
            disableDescendants(child.getId(), revokedCodes);
        }
    }

    /**
     * 注册事务提交后的会话撤销（确认记录①②：updateStatus 保持在事务内只改库，
     * 撤销调用在 afterCommit——事务回滚（含 default 租户拒绝）不外呼；
     * 服务身份绑定在 {@link TenantSessionRevocationGateway}，失败降级 WARN 不抛）。
     */
    private void registerAfterCommitRevocation(List<String> tenantCodes) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无活动事务（理论不可达：updateStatus 恒在事务内）：防御式直调，保持撤销语义不丢失
            log.warn("停用租户无活动事务，撤销调用退化为同步执行：tenantCodes={}", tenantCodes);
            tenantCodes.forEach(this::revokeTenantSessionsQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tenantCodes.forEach(TenantUseCase.this::revokeTenantSessionsQuietly);
            }
        });
    }

    /** 会话撤销调用（失败降级 WARN 不抛，确认记录①：不回滚停用，残留会话至多存活至 access TTL）。 */
    private void revokeTenantSessionsQuietly(String tenantCode) {
        try {
            TenantSessionRevocationGateway.RevocationOutcome outcome = revocationGateway.revokeByTenant(tenantCode);
            log.info("停用租户会话撤销完成：tenantCode={} revoked={} failed={}",
                    tenantCode, outcome.revokedCount(), outcome.failedCount());
        } catch (RuntimeException ex) {
            log.warn("停用租户会话撤销调用失败（降级不回滚停用，残留会话至多存活至 access TTL）：tenantCode={}",
                    tenantCode, ex);
        }
    }

    /** 分页查询（跨租户平台管理场景，显式旁路声明 + 审计 12.4.2）。 */
    @IgnoreTenant("tenant-list-lookup")
    public PageResult<SysTenant> page(PageQuery query, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.where(SysTenant::getTenantCode).like(like).or(SysTenant::getTenantName).like(like);
        }
        wrapper.orderBy(SysTenant::getTenantCode, true);
        return tenantRepository.findAll(wrapper, query.page(), query.size());
    }
}
