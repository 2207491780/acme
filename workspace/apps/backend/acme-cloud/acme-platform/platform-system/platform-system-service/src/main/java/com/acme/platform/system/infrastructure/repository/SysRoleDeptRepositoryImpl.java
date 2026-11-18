package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysRoleDept;
import com.acme.platform.system.infrastructure.mapper.SysRoleDeptMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 角色-部门绑定仓库实现（MyBatis-Flex 底座；重写/清理经旁路物理删除——绑定表唯一键决定，7.2.7）。 */
@Repository
public class SysRoleDeptRepositoryImpl extends FlexRepositorySupport<SysRoleDept>
        implements SysRoleDeptRepository {

    public SysRoleDeptRepositoryImpl(SysRoleDeptMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysRoleDept> findByRoleId(Long roleId) {
        return mapper().selectListByQuery(QueryWrapper.create().where(SysRoleDept::getRoleId).eq(roleId));
    }

    @Override
    public List<SysRoleDept> findByDeptId(Long deptId) {
        return mapper().selectListByQuery(QueryWrapper.create().where(SysRoleDept::getDeptId).eq(deptId));
    }

    @Override
    public boolean existsByDeptId(Long deptId) {
        return exists(QueryWrapper.create().where(SysRoleDept::getDeptId).eq(deptId));
    }

    @Override
    public int deleteByRoleId(Long roleId) {
        return forceDeleteByQuery(QueryWrapper.create().where(SysRoleDept::getRoleId).eq(roleId));
    }
}
