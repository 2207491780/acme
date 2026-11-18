package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysDictType;

/** 字典类型仓库（租户归属，租户列自动过滤）。 */
public interface SysDictTypeRepository extends FlexRepository<SysDictType> {

    boolean existsByDictCodeAndIdNot(String dictCode, Long id);
}
