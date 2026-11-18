package com.acme.framework.flex.repository;

import com.acme.common.contract.PageResult;
import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 数据访问仓储统一契约（架构设计 10.3：形状不变、底座替换——MyBatis-Flex）：
 * 审计填充 / 乐观锁 / 软删除 / 租户过滤由框架层监听器与 TenantFactory 承担（FR-008/009/010、12.4.1）。
 * 各服务仓储接口继承本契约并保持原有方法名（application 层注入不变）。
 */
public interface FlexRepository<T extends AuditBase> {

    /** 保存：无 id 新增；有 id 乐观锁更新（0 行=并发冲突，转 FlexError.STALE_WRITE）。 */
    T save(T entity);

    /** 批量保存（逐条 insert；绑定表整体替换等场景）。 */
    List<T> saveAll(List<T> entities);

    Optional<T> findById(Long id);

    List<T> findAllById(Collection<Long> ids);

    boolean existsById(Long id);

    /** 删除（软删除开启走逻辑删除；幂等——已删/不存在 0 行按成功处理）。 */
    void delete(T entity);

    void deleteById(Long id);

    List<T> findAll();

    List<T> findAll(QueryWrapper query);

    /** 条件分页（1-based 页码；count 查询独立剥离排序）。 */
    PageResult<T> findAll(QueryWrapper query, Integer page, Integer size);

    boolean exists(QueryWrapper query);

    /** 强制物理删除（绑定表唯一键重绑等场景，忽略软删除开关）。 */
    int forceDeleteByQuery(QueryWrapper query);
}
