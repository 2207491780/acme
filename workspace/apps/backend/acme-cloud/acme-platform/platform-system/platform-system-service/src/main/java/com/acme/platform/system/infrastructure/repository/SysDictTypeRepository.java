package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysDictType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 字典类型仓库（租户归属，@TenantId 自动过滤）。 */
public interface SysDictTypeRepository extends JpaRepository<SysDictType, Long>,
        JpaSpecificationExecutor<SysDictType> {

    boolean existsByDictCodeAndIdNot(String dictCode, Long id);
}
