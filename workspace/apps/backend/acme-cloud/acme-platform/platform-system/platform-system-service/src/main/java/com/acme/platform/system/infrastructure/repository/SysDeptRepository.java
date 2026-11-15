package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysDept;

import java.util.List;

/** 部门仓库（租户归属，租户列自动过滤 12.4.1）。 */
public interface SysDeptRepository extends FlexRepository<SysDept> {

    boolean existsByParentId(Long parentId);

    /** 子树部门（path 前缀匹配，含自身；path 由用例层在创建/重挂时回填）。 */
    List<SysDept> findSubtree(String pathPrefix);

    /**
     * 子树部门 id 集（含自身；用户列表 deptId 入参递归过滤用）。
     * fail-closed 口径（REQ-20261114-120104 确认记录⑪）：部门不存在 / 跨租户（租户过滤不可见）→ 空集。
     */
    List<Long> findSubtreeIds(Long deptId);

    /**
     * 回填物化路径（创建装配步骤：insert 后雪花 id 已定，path 依赖 id 二次回填；
     * 定向更新不递增乐观锁版本——装配回填非业务修改）。
     */
    void updatePath(Long id, String path);
}
