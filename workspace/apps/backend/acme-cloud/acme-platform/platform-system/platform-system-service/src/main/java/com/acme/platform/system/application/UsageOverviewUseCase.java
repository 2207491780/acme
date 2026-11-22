package com.acme.platform.system.application;

import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.UsageOverviewView;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysTenantPackage;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台运营总览用例（REQ-20261122-233426 确认⑥）：跨租户实时旁路聚合——租户总数 + 派生四视觉态
 * 计数 + 套餐分布 + 用量 TopN（storageBytes 降序，N=20）。
 *
 * <p>旁路口径：整个聚合作用域 @IgnoreTenant 显式声明 + 审计 12.4.2（TenantQuotaUseCase.usage
 * 同款）；逐租户用量直接复用 {@link TenantQuotaUseCase#usage(Long)}（storageBytes=sys_file 未删
 * size 总和 + userCount + 三级覆盖链配额与来源，TENANT-4 确认④既有面零改造）；数据量小先实时
 * （对齐 TENANT-4 ④口径，定时聚合留后续）。packageDistribution（TL 裁决④补列）：按绑定套餐
 * 聚合租户数，未绑定租户聚合为 packageId/packageName null 项，tenantCount 降序（msm 口径）。</p>
 *
 * <p>四视觉态（TENANT-4 确认②派生口径，读时判定无定时任务）：停用正交优先；未停用下
 * expires 未到（或无 expires）=启用中；expires 过而 grace 未过=宽限期；宽限也过（或无 grace）=
 * 已过期。</p>
 */
@Service
public class UsageOverviewUseCase {

    /** 用量 TopN 条数（确认⑥：topN=20 按 storageBytes 降序）。 */
    static final int TOP_N = 20;

    private final SysTenantRepository tenantRepository;
    private final SysTenantPackageRepository tenantPackageRepository;
    private final TenantQuotaUseCase tenantQuotaUseCase;

    public UsageOverviewUseCase(SysTenantRepository tenantRepository,
                                SysTenantPackageRepository tenantPackageRepository,
                                TenantQuotaUseCase tenantQuotaUseCase) {
        this.tenantRepository = tenantRepository;
        this.tenantPackageRepository = tenantPackageRepository;
        this.tenantQuotaUseCase = tenantQuotaUseCase;
    }

    /**
     * 运营总览聚合（跨租户平台运营场景，显式旁路声明 + 审计 12.4.2）：totalTenants=目录全量口径；
     * statusCounts=派生四视觉态计数；packageDistribution=按绑定套餐聚合（未绑定=null 项，
     * tenantCount 降序、同数按 packageId 升序 nulls-first 稳定输出）；tenantUsages=全量租户
     * 逐个聚合后按 storageBytes 降序取前 {@value TOP_N}（同字节并列按 tenantCode 升序稳定输出）。
     */
    @IgnoreTenant("tenant-usage-overview-aggregation")
    public UsageOverviewView overview() {
        List<SysTenant> tenants = tenantRepository.findAll(
                QueryWrapper.create().orderBy(SysTenant::getTenantCode, true));
        Instant now = Instant.now();
        long enabled = 0;
        long grace = 0;
        long expired = 0;
        long disabled = 0;
        Map<Long, Long> tenantCountsByPackageId = new HashMap<>();
        for (SysTenant tenant : tenants) {
            switch (derivedStatus(tenant, now)) {
                case "ENABLED" -> enabled++;
                case "GRACE" -> grace++;
                case "EXPIRED" -> expired++;
                default -> disabled++;
            }
            if (tenant.getPackageId() != null) {
                tenantCountsByPackageId.merge(tenant.getPackageId(), 1L, Long::sum);
            }
        }
        List<UsageOverviewView.PackageDistributionItem> distribution = packageDistribution(
                tenants.size() - tenantCountsByPackageId.values().stream().mapToLong(Long::longValue).sum(),
                tenantCountsByPackageId);
        List<UsageOverviewView.TenantUsage> usages = tenants.stream()
                .map(this::usageOf)
                .sorted(Comparator.comparingLong(UsageOverviewView.TenantUsage::storageBytes).reversed()
                        .thenComparing(UsageOverviewView.TenantUsage::tenantCode))
                .limit(TOP_N)
                .toList();
        return new UsageOverviewView(tenants.size(),
                new UsageOverviewView.StatusCounts(enabled, grace, expired, disabled),
                distribution, usages);
    }

    /**
     * 套餐分布组装（TL 裁决④，msm 口径）：未绑定租户聚合为单个 null 项（packageId/packageName
     * 均 null）；绑定项 packageName 批量解析（套餐行已删/缺失=null 容错）；tenantCount 降序、
     * 同数按 packageId 升序 nulls-first 稳定输出。
     */
    private List<UsageOverviewView.PackageDistributionItem> packageDistribution(
            long unboundTenantCount, Map<Long, Long> tenantCountsByPackageId) {
        Set<Long> packageIds = tenantCountsByPackageId.keySet();
        Map<Long, String> packageNamesById = packageIds.isEmpty() ? Map.of()
                : tenantPackageRepository.findAllById(packageIds).stream()
                        .collect(Collectors.toMap(SysTenantPackage::getId, SysTenantPackage::getPackageName));
        List<UsageOverviewView.PackageDistributionItem> items = new ArrayList<>();
        if (unboundTenantCount > 0) {
            items.add(new UsageOverviewView.PackageDistributionItem(null, null, unboundTenantCount));
        }
        for (Map.Entry<Long, Long> entry : tenantCountsByPackageId.entrySet()) {
            items.add(new UsageOverviewView.PackageDistributionItem(entry.getKey(),
                    packageNamesById.get(entry.getKey()), entry.getValue()));
        }
        items.sort(Comparator.comparingLong(UsageOverviewView.PackageDistributionItem::tenantCount).reversed()
                .thenComparing(UsageOverviewView.PackageDistributionItem::packageId,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return items;
    }

    /** 逐租户用量聚合（复用 TenantQuotaUseCase.usage 旁路既有面，确认⑥零改造复用）。 */
    private UsageOverviewView.TenantUsage usageOf(SysTenant tenant) {
        var usage = tenantQuotaUseCase.usage(tenant.getId());
        return new UsageOverviewView.TenantUsage(usage.tenantId(), usage.tenantCode(),
                tenant.getTenantName(), derivedStatus(tenant, Instant.now()), usage.storageBytes(),
                usage.quotaStorageBytes(), usage.quotaSource(), usage.userCount());
    }

    /**
     * 派生四视觉态（TENANT-4 确认②口径；停用正交优先，读时判定）：
     * DISABLED=已停用；expires 未到/无 expires=ENABLED；expires 过而 grace 未过=GRACE；否则 EXPIRED。
     */
    static String derivedStatus(SysTenant tenant, Instant now) {
        if (tenant.getStatus() == EnableStatus.DISABLED) {
            return "DISABLED";
        }
        boolean expiresPassed = tenant.getExpiresAt() != null && tenant.getExpiresAt().isBefore(now);
        if (!expiresPassed) {
            return "ENABLED";
        }
        return tenant.getGraceUntil() != null && tenant.getGraceUntil().isAfter(now) ? "GRACE" : "EXPIRED";
    }
}
