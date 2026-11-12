package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysDictItem;

import java.util.List;

/** 字典项仓库（租户归属，租户列自动过滤；dictTypeId 从属引用显式路径）。 */
public interface SysDictItemRepository extends FlexRepository<SysDictItem> {

    boolean existsByDictType_IdAndItemValueAndIdNot(Long dictTypeId, String itemValue, Long id);

    boolean existsByDictType_Id(Long dictTypeId);

    List<SysDictItem> findByDictType_IdOrderBySortOrderAscIdAsc(Long dictTypeId);
}
