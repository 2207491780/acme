package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.infrastructure.mapper.SysDictItemMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 字典项仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysDictItemRepositoryImpl extends FlexRepositorySupport<SysDictItem>
        implements SysDictItemRepository {

    public SysDictItemRepositoryImpl(SysDictItemMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByDictType_IdAndItemValueAndIdNot(Long dictTypeId, String itemValue, Long id) {
        return exists(QueryWrapper.create()
                .where(SysDictItem::getDictTypeId).eq(dictTypeId)
                .and(SysDictItem::getItemValue).eq(itemValue)
                .and(SysDictItem::getId).ne(id));
    }

    @Override
    public boolean existsByDictType_Id(Long dictTypeId) {
        return exists(QueryWrapper.create().where(SysDictItem::getDictTypeId).eq(dictTypeId));
    }

    @Override
    public List<SysDictItem> findByDictType_IdOrderBySortOrderAscIdAsc(Long dictTypeId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysDictItem::getDictTypeId).eq(dictTypeId)
                .orderBy(SysDictItem::getSortOrder, true)
                .orderBy(SysDictItem::getId, true));
    }
}
