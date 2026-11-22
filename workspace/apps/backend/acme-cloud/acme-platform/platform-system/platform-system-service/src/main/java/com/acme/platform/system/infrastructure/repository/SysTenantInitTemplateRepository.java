package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysTenantInitTemplate;

import java.util.Optional;

/** 开通模板仓库（平台级表，不参与租户过滤；REQ-20261122-233426 确认②）。 */
public interface SysTenantInitTemplateRepository extends FlexRepository<SysTenantInitTemplate> {

    /** 按模板编码取（init 命中判定数据源；软删行被逻辑删除过滤视同不存在）。 */
    Optional<SysTenantInitTemplate> findByCode(String code);

    boolean existsByCode(String code);

    /** 重码守卫自排除（update 撞自身码不算重码）。 */
    boolean existsByCodeAndIdNot(String code, Long id);
}
