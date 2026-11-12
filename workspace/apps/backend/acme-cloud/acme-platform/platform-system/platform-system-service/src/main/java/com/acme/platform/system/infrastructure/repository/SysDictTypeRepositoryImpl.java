package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.infrastructure.mapper.SysDictTypeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

/** 字典类型仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysDictTypeRepositoryImpl extends FlexRepositorySupport<SysDictType>
        implements SysDictTypeRepository {

    public SysDictTypeRepositoryImpl(SysDictTypeMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByDictCodeAndIdNot(String dictCode, Long id) {
        return exists(QueryWrapper.create()
                .where(SysDictType::getDictCode).eq(dictCode)
                .and(SysDictType::getId).ne(id));
    }
}
