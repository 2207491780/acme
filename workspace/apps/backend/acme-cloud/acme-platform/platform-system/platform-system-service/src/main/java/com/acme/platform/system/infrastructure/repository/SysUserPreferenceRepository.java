package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysUserPreference;

import java.util.Optional;

/** 用户偏好仓库（本人单行按 user_id 定位，无租户过滤 Q-D04）。 */
public interface SysUserPreferenceRepository extends FlexRepository<SysUserPreference> {

    /** 本人偏好行（未设置返回 empty——调用方回默认偏好）。 */
    Optional<SysUserPreference> findByUserId(Long userId);
}
