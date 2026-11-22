package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 平台运营总览视图（GET /admin/v1/system/tenants/usage-overview，REQ-20261122-233426 确认⑥）：
 * 跨租户实时旁路聚合（数据量小先实时，对齐 TENANT-4 ④口径，无定时聚合）。
 *
 * @param totalTenants       全平台租户总数（sys_tenant 目录全量口径）
 * @param statusCounts       派生四视觉态计数（启用中/宽限期/已过期/已停用；读时派生无定时任务，
 *                           TENANT-4 确认②同口径）
 * @param packageDistribution 套餐分布（确认⑥可选列，TL 裁决④补列）：按绑定套餐聚合租户数，
 *                           未绑定租户聚合为 packageId/packageName null 项；tenantCount 降序
 * @param tenantUsages       用量 TopN（N=20，按 storageBytes 降序；逐租户复用 TenantQuotaUseCase.usage
 *                           旁路聚合，确认⑥）
 */
public record UsageOverviewView(long totalTenants,
                                StatusCounts statusCounts,
                                List<PackageDistributionItem> packageDistribution,
                                List<TenantUsage> tenantUsages) {

    /**
     * 派生四视觉态计数（wire 键 enabled/grace/expired/disabled；停用正交优先，
     * 宽限期=expires 过而 grace 未过，宽限也过=已过期）。
     *
     * @param enabled  启用中（未停用且未到期，或无有效期）
     * @param grace    宽限期（expires 过、grace 未过；仍可登录派生态）
     * @param expired  已过期（宽限也过，或 expires 过且无 grace）
     * @param disabled 已停用（status=DISABLED 正交优先）
     */
    public record StatusCounts(long enabled, long grace, long expired, long disabled) {
    }

    /**
     * 套餐分布项（TL 裁决④，形状照 frontend msm 口径）。
     *
     * @param packageId   套餐 ID（wire 字符串化雪花 ID；null=未绑定套餐租户聚合项）
     * @param packageName 套餐名称（实时冗余解析；套餐行已删/缺失=null）
     * @param tenantCount 绑定该套餐的租户数
     */
    public record PackageDistributionItem(Long packageId, String packageName, long tenantCount) {
    }

    /**
     * 租户用量行（逐租户聚合复用 TenantQuotaUseCase.usage 旁路口径）。
     *
     * @param tenantId     租户 ID（wire 字符串化雪花 ID）
     * @param tenantCode   租户代码
     * @param tenantName   租户名称
     * @param status       派生四视觉态（ENABLED/GRACE/EXPIRED/DISABLED，与 statusCounts 同词汇）
     * @param storageBytes 存储用量字节（sys_file 未删行 size 总和；sys_file 表不可达降级 0）
     * @param quotaBytes   解析后有效存储配额字节（三级覆盖链租户覆盖→套餐→平台缺省；0=不限）
     * @param quotaSource  配额来源层级（TENANT/PACKAGE/PLATFORM）
     * @param userCount    租户内用户数
     */
    public record TenantUsage(Long tenantId, String tenantCode, String tenantName, String status,
                              long storageBytes, long quotaBytes, String quotaSource, long userCount) {
    }
}
