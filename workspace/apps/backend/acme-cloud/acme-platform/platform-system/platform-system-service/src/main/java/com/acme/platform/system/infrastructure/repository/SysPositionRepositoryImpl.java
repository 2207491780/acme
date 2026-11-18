package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysPosition;
import com.acme.platform.system.infrastructure.mapper.SysPositionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 岗位仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysPositionRepositoryImpl extends FlexRepositorySupport<SysPosition>
        implements SysPositionRepository {

    public SysPositionRepositoryImpl(SysPositionMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByPositionCode(String positionCode) {
        return exists(QueryWrapper.create().where(SysPosition::getPositionCode).eq(positionCode));
    }

    @Override
    public boolean existsByPositionCodeAndIdNot(String positionCode, Long id) {
        return exists(QueryWrapper.create()
                .where(SysPosition::getPositionCode).eq(positionCode)
                .and(SysPosition::getId).ne(id));
    }

    @Override
    public List<SysPosition> findByStatus(EnableStatus status) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysPosition::getStatus).eq(status)
                .orderBy(SysPosition::getId, true));
    }
}
