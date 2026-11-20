package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysTenantPackageMenu;
import com.acme.platform.system.infrastructure.mapper.SysTenantPackageMenuMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 套餐-菜单绑定仓库实现（MyBatis-Flex 底座；全量重绑经旁路物理删除，7.2.7 绑定表口径）。 */
@Repository
public class SysTenantPackageMenuRepositoryImpl extends FlexRepositorySupport<SysTenantPackageMenu>
        implements SysTenantPackageMenuRepository {

    public SysTenantPackageMenuRepositoryImpl(SysTenantPackageMenuMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysTenantPackageMenu> findByPackageId(Long packageId) {
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysTenantPackageMenu::getPackageId).eq(packageId));
    }

    @Override
    public List<SysTenantPackageMenu> findByPackageIdIn(Collection<Long> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysTenantPackageMenu::getPackageId).in(packageIds));
    }

    @Override
    public int deleteByPackageId(Long packageId) {
        return forceDeleteByQuery(QueryWrapper.create().where(SysTenantPackageMenu::getPackageId).eq(packageId));
    }
}
