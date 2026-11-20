package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysTenantPackage;
import com.acme.platform.system.infrastructure.mapper.SysTenantPackageMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

/** 套餐仓库实现（MyBatis-Flex 底座；平台级表不过滤租户）。 */
@Repository
public class SysTenantPackageRepositoryImpl extends FlexRepositorySupport<SysTenantPackage>
        implements SysTenantPackageRepository {

    public SysTenantPackageRepositoryImpl(SysTenantPackageMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByPackageName(String packageName) {
        return exists(QueryWrapper.create().where(SysTenantPackage::getPackageName).eq(packageName));
    }

    @Override
    public boolean existsByPackageNameAndIdNot(String packageName, Long id) {
        return exists(QueryWrapper.create().where(SysTenantPackage::getPackageName).eq(packageName)
                .and(SysTenantPackage::getId).ne(id));
    }
}
