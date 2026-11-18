package com.acme.framework.flex.repository;

import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.support.AuditBase;
import com.acme.framework.flex.support.FlexError;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 仓储实现基类（JPA 仓储等效语义——10.3.1 幂等、FR-008/009/010、12.4.1）：
 * 保存=insert（无 id）或乐观锁更新（0 行=并发冲突转 FlexError.STALE_WRITE，成功后同步 SQL 端自增的
 * 版本回实体）；删除统一经 FlexDeletes（软删开关 / 绑定表物理删除）；审计填充、租户过滤由框架监听器完成。
 */
public abstract class FlexRepositorySupport<T extends AuditBase> implements FlexRepository<T> {

    private final BaseMapper<T> mapper;

    protected FlexRepositorySupport(BaseMapper<T> mapper) {
        this.mapper = mapper;
    }

    protected final BaseMapper<T> mapper() {
        return mapper;
    }

    @Override
    public T save(T entity) {
        if (entity.getId() == null) {
            mapper.insert(entity);
            return entity;
        }
        int rows = mapper.update(entity, false);
        if (rows == 0) {
            throw new BizException(FlexError.STALE_WRITE);
        }
        // Flex 在 SQL 端自增版本（version = version + 1）不写回实体，此处负责实体版本同步
        entity.setVersion(entity.getVersion() + 1);
        return entity;
    }

    @Override
    public List<T> saveAll(List<T> entities) {
        for (T entity : entities) {
            save(entity);
        }
        return entities;
    }

    @Override
    public Optional<T> findById(Long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public List<T> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectListByIds(ids);
    }

    @Override
    public boolean existsById(Long id) {
        return mapper.selectCountByQuery(QueryWrapper.create().where(new QueryColumn("id").eq(id))) > 0;
    }

    @Override
    public void delete(T entity) {
        FlexDeletes.deleteById(mapper, entity.getId());
    }

    @Override
    public void deleteById(Long id) {
        FlexDeletes.deleteById(mapper, id);
    }

    @Override
    public List<T> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create());
    }

    @Override
    public List<T> findAll(QueryWrapper query) {
        return mapper.selectListByQuery(query);
    }

    @Override
    public PageResult<T> findAll(QueryWrapper query, Integer page, Integer size) {
        var result = mapper.paginate(page, size, query);
        return new PageResult<>(result.getRecords(), result.getTotalRow());
    }

    @Override
    public boolean exists(QueryWrapper query) {
        return mapper.selectCountByQuery(query) > 0;
    }

    @Override
    public int forceDeleteByQuery(QueryWrapper query) {
        return FlexDeletes.forceDeleteByQuery(mapper, query);
    }
}
