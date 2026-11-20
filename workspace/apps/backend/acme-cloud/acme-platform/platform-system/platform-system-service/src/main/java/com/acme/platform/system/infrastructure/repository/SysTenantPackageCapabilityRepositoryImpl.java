package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysTenantPackageCapability;
import com.acme.platform.system.infrastructure.mapper.SysTenantPackageCapabilityMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 套餐-能力绑定仓库实现（MyBatis-Flex 底座；全量重绑经旁路物理删除，7.2.7 绑定表口径）。 */
@Repository
public class SysTenantPackageCapabilityRepositoryImpl extends FlexRepositorySupport<SysTenantPackageCapability>
        implements SysTenantPackageCapabilityRepository {

    public SysTenantPackageCapabilityRepositoryImpl(SysTenantPackageCapabilityMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysTenantPackageCapability> findByPackageId(Long packageId) {
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysTenantPackageCapability::getPackageId).eq(packageId));
    }

    @Override
    public int deleteByPackageId(Long packageId) {
        return forceDeleteByQuery(
                QueryWrapper.create().where(SysTenantPackageCapability::getPackageId).eq(packageId));
    }
}
