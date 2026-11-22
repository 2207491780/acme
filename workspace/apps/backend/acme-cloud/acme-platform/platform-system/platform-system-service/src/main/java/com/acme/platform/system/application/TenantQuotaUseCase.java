package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.TenantQuotaView;
import com.acme.platform.system.api.dto.AdminTenantUsageView;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysTenantPackage;
import com.acme.platform.system.domain.SysTenantQuota;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantQuotaRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户配额解析用例（REQ-20261122-150802 确认③④⑦）。
 *
 * <p>三级覆盖链 resolve=租户覆盖（sys_tenant_quota）→ 套餐默认（sys_tenant_package.
 * quota_storage_bytes）→ 平台缺省（framework-storage StorageProperties defaultBytes，缺省
 * 1GiB、0=不限，单源不变）；一期配额项仅存储（用户数/流程实例/调度任务数=后续 REQ）。</p>
 *
 * <p>setTenantQuota=租户覆盖写面（非空=覆盖 0=不限 / null=清除继承——覆盖行保留仅置空回落套餐）。
 * usage=运营用量实时旁路聚合（确认④：数据量小先实时，定时聚合留后续；@IgnoreTenant 显式旁路 +
 * 审计 12.4.2）——storageBytes 取 sys_file 未删行 size 总和（文件数据归 platform-infra 迁移域，
 * 本服务经同库数据面直查，表不可达降级 0 + WARN，tester AMB-5 口径）。</p>
 */
@Service
public class TenantQuotaUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaUseCase.class);

    /** 配额来源层级（usage/quota 视图 quotaSource wire 枚举名）。 */
    public enum QuotaSource {
        /** 租户覆盖（sys_tenant_quota）。 */
        TENANT,
        /** 套餐默认（sys_tenant_package.quota_storage_bytes）。 */
        PACKAGE,
        /** 平台缺省（StorageProperties.defaultBytes）。 */
        PLATFORM
    }

    /** 配额解析结果载体（有效值 + 来源层级）。 */
    public record QuotaResolution(long quotaStorageBytes, QuotaSource source) {
    }

    private final SysTenantRepository tenantRepository;
    private final SysTenantQuotaRepository tenantQuotaRepository;
    private final SysTenantPackageRepository tenantPackageRepository;
    private final SysUserRepository userRepository;
    private final StorageProperties storageProperties;
    private final JdbcTemplate jdbcTemplate;

    public TenantQuotaUseCase(SysTenantRepository tenantRepository,
                              SysTenantQuotaRepository tenantQuotaRepository,
                              SysTenantPackageRepository tenantPackageRepository,
                              SysUserRepository userRepository,
                              StorageProperties storageProperties,
                              JdbcTemplate jdbcTemplate) {
        this.tenantRepository = tenantRepository;
        this.tenantQuotaRepository = tenantQuotaRepository;
        this.tenantPackageRepository = tenantPackageRepository;
        this.userRepository = userRepository;
        this.storageProperties = storageProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按租户代码解析有效存储配额（三级覆盖链，确认③；内部契约 /internal/v1/tenants/quota 数据源
     * 与单体本地短路共用）。未知租户显式失败 TENANT_NOT_FOUND（404；消费方降级口径自担——
     * FileUseCase 解析不可达回落平台缺省）。
     */
    @IgnoreTenant("tenant-quota-resolution")
    public TenantQuotaView resolveQuota(String tenantCode) {
        SysTenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        QuotaResolution resolution = resolveChain(tenant);
        return new TenantQuotaView(resolution.quotaStorageBytes(), resolution.source().name());
    }

    /** 单租户解析后有效配额值（管理面视图富化，确认⑦「解析后有效值」）。 */
    public long resolveQuotaBytes(SysTenant tenant) {
        return resolveChain(tenant).quotaStorageBytes();
    }

    /** 批量解析（列表/树视图富化，免 N+1：覆盖行与套餐行各一次批量取回，确认⑦）。 */
    public Map<Long, Long> resolveQuotaBytesByTenantId(Collection<SysTenant> tenants) {
        if (tenants == null || tenants.isEmpty()) {
            return Map.of();
        }
        List<Long> tenantIds = tenants.stream().map(SysTenant::getId).toList();
        Map<Long, Long> overrideByTenant = tenantQuotaRepository.findByTenantIdIn(tenantIds).stream()
                .filter(row -> row.getQuotaStorageBytes() != null)
                .collect(Collectors.toMap(SysTenantQuota::getTenantId, SysTenantQuota::getQuotaStorageBytes));
        Set<Long> packageIds = tenants.stream().map(SysTenant::getPackageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> packageQuotaById = packageIds.isEmpty() ? Map.of()
                : tenantPackageRepository.findAllById(packageIds).stream()
                        .filter(pkg -> pkg.getQuotaStorageBytes() != null)
                        .collect(Collectors.toMap(SysTenantPackage::getId, SysTenantPackage::getQuotaStorageBytes));
        long platformDefault = storageProperties.getQuota().getDefaultBytes();
        Map<Long, Long> result = new HashMap<>(tenants.size());
        for (SysTenant tenant : tenants) {
            Long override = overrideByTenant.get(tenant.getId());
            Long packageQuota = tenant.getPackageId() == null ? null : packageQuotaById.get(tenant.getPackageId());
            result.put(tenant.getId(), override != null ? override
                    : packageQuota != null ? packageQuota : platformDefault);
        }
        return result;
    }

    /** 三级覆盖链解析（确认③）：覆盖行 quota NULL=继承套餐 → 套餐 quota NULL=平台缺省。 */
    private QuotaResolution resolveChain(SysTenant tenant) {
        SysTenantQuota override = tenantQuotaRepository.findByTenantId(tenant.getId()).orElse(null);
        if (override != null && override.getQuotaStorageBytes() != null) {
            return new QuotaResolution(override.getQuotaStorageBytes(), QuotaSource.TENANT);
        }
        if (tenant.getPackageId() != null) {
            Long packageQuota = tenantPackageRepository.findById(tenant.getPackageId())
                    .map(SysTenantPackage::getQuotaStorageBytes)
                    .filter(Objects::nonNull)
                    .orElse(null);
            if (packageQuota != null) {
                return new QuotaResolution(packageQuota, QuotaSource.PACKAGE);
            }
        }
        return new QuotaResolution(storageProperties.getQuota().getDefaultBytes(), QuotaSource.PLATFORM);
    }

    /**
     * 租户配额覆盖写面（确认③⑦）：quotaStorageBytes 非空=覆盖（0=不限）；null=清除继承——
     * 覆盖行保留仅置空（NULL=继承套餐语义），无覆盖行时幂等无操作。
     */
    @Transactional
    public void setTenantQuota(Long tenantId, Long quotaStorageBytes) {
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        SysTenantQuota row = tenantQuotaRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> new SysTenantQuota(tenant.getId()));
        if (quotaStorageBytes == null && row.getId() == null) {
            // 清除继承且无覆盖行：幂等无操作（解析链本就回落套餐/平台缺省）
            return;
        }
        row.setQuotaStorageBytes(quotaStorageBytes);
        tenantQuotaRepository.save(row);
    }

    /**
     * 租户用量（GET /{id}/usage 数据源，确认④⑧）：跨租户运营聚合，显式旁路声明 + 审计 12.4.2；
     * storageBytes=sys_file 未删行 size 总和（文件数据归 platform-infra 迁移域，同库数据面直查，
     * 表不可达（分布式 platform_system schema 无此表）降级 0 + WARN，不阻断视图）+ userCount。
     */
    @IgnoreTenant("tenant-usage-aggregation")
    public AdminTenantUsageView usage(Long tenantId) {
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
        String tenantCode = tenant.getTenantCode();
        long storageBytes = sumFileStorageBytes(tenantCode);
        long userCount = userRepository.countByTenantId(tenantCode);
        QuotaResolution resolution = resolveChain(tenant);
        return new AdminTenantUsageView(tenantId, tenantCode, storageBytes, userCount,
                resolution.quotaStorageBytes(), resolution.source().name());
    }

    /** sys_file 未删行 size 总和（同库数据面直查；表不在本服务 schema 时降级 0 + WARN）。 */
    private long sumFileStorageBytes(String tenantCode) {
        try {
            Long total = jdbcTemplate.queryForObject(
                    "select coalesce(sum(size), 0) from sys_file where deleted = false and tenant_id = ?",
                    Long.class, tenantCode);
            return total == null ? 0L : total;
        } catch (DataAccessException ex) {
            log.warn("租户存储用量聚合失败（sys_file 表不在本服务数据面，按 0 降级）：tenantCode={}",
                    tenantCode, ex);
            return 0L;
        }
    }
}
