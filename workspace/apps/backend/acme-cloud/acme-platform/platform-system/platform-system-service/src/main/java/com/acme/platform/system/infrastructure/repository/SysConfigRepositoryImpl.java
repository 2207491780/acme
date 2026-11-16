package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.infrastructure.mapper.SysConfigMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 租户级系统配置仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysConfigRepositoryImpl extends FlexRepositorySupport<SysConfig>
        implements SysConfigRepository {

    public SysConfigRepositoryImpl(SysConfigMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SysConfig> findCurrent() {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()));
    }

    @Override
    public Optional<SysConfig> findByTenantCode(String tenantCode) {
        return Optional.ofNullable(mapper().selectOneByQuery(
                QueryWrapper.create().where(SysConfig::getTenantId).eq(tenantCode)));
    }
}