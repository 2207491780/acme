package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.mapper.SysUserMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

/** 用户仓库实现（MyBatis-Flex 底座；租户过滤由框架注入，12.4.1）。 */
@Repository
public class SysUserRepositoryImpl extends FlexRepositorySupport<SysUser> implements SysUserRepository {

    public SysUserRepositoryImpl(SysUserMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByUsername(String username) {
        return exists(QueryWrapper.create().where(SysUser::getUsername).eq(username));
    }

    @Override
    public SysUser findByUsername(String username) {
        return mapper().selectOneByQuery(QueryWrapper.create().where(SysUser::getUsername).eq(username));
    }
}
