package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysCapability;
import com.acme.platform.system.infrastructure.mapper.SysCapabilityMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 服务能力仓库实现（MyBatis-Flex 底座；启用过滤 + id asc 排序，REQ-20261121-054613 确认③④）。 */
@Repository
public class SysCapabilityRepositoryImpl extends FlexRepositorySupport<SysCapability>
        implements SysCapabilityRepository {

    public SysCapabilityRepositoryImpl(SysCapabilityMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysCapability> findEnabled() {
        return mapper().selectListByQuery(enabledWrapper().orderBy(SysCapability::getId, true));
    }

    @Override
    public List<SysCapability> findEnabledByIdIn(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(enabledWrapper().and(SysCapability::getId).in(ids)
                .orderBy(SysCapability::getId, true));
    }

    @Override
    public List<SysCapability> findEnabledByCodeIn(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(enabledWrapper().and(SysCapability::getCapabilityCode).in(codes));
    }

    private QueryWrapper enabledWrapper() {
        return QueryWrapper.create().where(SysCapability::getStatus).eq(EnableStatus.ENABLED);
    }
}
