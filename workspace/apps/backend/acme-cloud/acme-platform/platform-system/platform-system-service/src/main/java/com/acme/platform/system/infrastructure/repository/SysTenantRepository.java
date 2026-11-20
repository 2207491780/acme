package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysTenant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 租户仓库（平台级表，不参与租户过滤）。 */
public interface SysTenantRepository extends FlexRepository<SysTenant> {

    /**
     * 租户总数（REQ-20261119-011524 首页概览，确认记录②）：sys_tenant 平台目录表无租户列，
     * 直查即全平台口径（无旁路需求）。
     */
    long countAll();

    boolean existsByTenantCode(String tenantCode);

    Optional<SysTenant> findByTenantCode(String tenantCode);

    /** 按租户代码批量取（S14 N+1 收敛：画像批量面停用级联一次取回；未知代码不在结果中）。 */
    List<SysTenant> findByTenantCodeIn(Collection<String> tenantCodes);

    /** 套餐占用判定（删除守卫；REQ-20261121-022910）。 */
    boolean existsByPackageId(Long packageId);

    /** 按套餐批量取租户（分页 tenantCount 聚合一次取回；REQ-20261121-022910）。 */
    List<SysTenant> findByPackageIdIn(Collection<Long> packageIds);
}
