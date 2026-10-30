package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysDictItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/** 字典项仓库（租户归属，@TenantId 自动过滤；dictType 关联遍历用下划线显式路径）。 */
public interface SysDictItemRepository extends JpaRepository<SysDictItem, Long>,
        JpaSpecificationExecutor<SysDictItem> {

    boolean existsByDictType_IdAndItemValueAndIdNot(Long dictTypeId, String itemValue, Long id);

    boolean existsByDictType_Id(Long dictTypeId);

    List<SysDictItem> findByDictType_IdOrderBySortOrderAscIdAsc(Long dictTypeId);
}
