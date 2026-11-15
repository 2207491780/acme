package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysPosition;

import java.util.List;

/** 岗位仓库（租户归属，租户列自动过滤）。 */
public interface SysPositionRepository extends FlexRepository<SysPosition> {

    boolean existsByPositionCode(String positionCode);

    boolean existsByPositionCodeAndIdNot(String positionCode, Long id);

    List<SysPosition> findByStatus(EnableStatus status);
}
