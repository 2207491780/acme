package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.infrastructure.mapper.SysDeptMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 部门仓库实现（MyBatis-Flex 底座；租户过滤由框架注入，12.4.1）。 */
@Repository
public class SysDeptRepositoryImpl extends FlexRepositorySupport<SysDept> implements SysDeptRepository {

    public SysDeptRepositoryImpl(SysDeptMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByParentId(Long parentId) {
        return exists(QueryWrapper.create().where(SysDept::getParentId).eq(parentId));
    }

    @Override
    public List<SysDept> findSubtree(String pathPrefix) {
        // 前缀含尾斜杠（"/1/" 形态），LIKE "/1/%" 同时命中自身与子孙；path 仅由本服务维护，无通配注入面
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysDept::getPath).like(pathPrefix + "%"));
    }

    @Override
    public List<Long> findSubtreeIds(Long deptId) {
        // fail-closed：目标部门不存在 / 跨租户（租户过滤 findById 空）→ 空集，调用方直接空页
        return findById(deptId)
                .map(dept -> findSubtree(dept.getPath()).stream().map(SysDept::getId).toList())
                .orElse(List.of());
    }

    @Override
    public void updatePath(Long id, String path) {
        mapper().updateByQuery(SysDept.pathTemplate(path), QueryWrapper.create().where(SysDept::getId).eq(id));
    }
}
