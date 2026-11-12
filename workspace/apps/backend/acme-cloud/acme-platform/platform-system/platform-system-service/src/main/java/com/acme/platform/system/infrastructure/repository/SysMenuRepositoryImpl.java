package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.infrastructure.mapper.SysMenuMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 菜单仓库实现（MyBatis-Flex 底座；平台级目录不过滤租户）。 */
@Repository
public class SysMenuRepositoryImpl extends FlexRepositorySupport<SysMenu> implements SysMenuRepository {

    public SysMenuRepositoryImpl(SysMenuMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByParentId(Long parentId) {
        return exists(QueryWrapper.create().where(SysMenu::getParentId).eq(parentId));
    }

    @Override
    public List<SysMenu> findAllByOrderBySortOrderAscIdAsc() {
        return mapper().selectListByQuery(QueryWrapper.create()
                .orderBy(SysMenu::getSortOrder, true)
                .orderBy(SysMenu::getId, true));
    }
}
