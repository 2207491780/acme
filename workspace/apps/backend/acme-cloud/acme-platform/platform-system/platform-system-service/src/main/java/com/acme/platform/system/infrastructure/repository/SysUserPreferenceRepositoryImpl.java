package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysUserPreference;
import com.acme.platform.system.infrastructure.mapper.SysUserPreferenceMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 用户偏好仓库实现（MyBatis-Flex 底座；无租户列不触发租户过滤）。 */
@Repository
public class SysUserPreferenceRepositoryImpl extends FlexRepositorySupport<SysUserPreference>
        implements SysUserPreferenceRepository {

    public SysUserPreferenceRepositoryImpl(SysUserPreferenceMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SysUserPreference> findByUserId(Long userId) {
        return Optional.ofNullable(mapper().selectOneByQuery(
                QueryWrapper.create().where(SysUserPreference::getUserId).eq(userId)));
    }
}
