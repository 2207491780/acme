package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.mapper.SysFileMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 平台文件仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysFileRepositoryImpl extends FlexRepositorySupport<SysFile>
        implements SysFileRepository {

    public SysFileRepositoryImpl(SysFileMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysFile> findSoftDeletedExpiredBefore(Instant cutoff) {
        // 软删行查询走逻辑删除旁路（默认查询面恒过滤 deleted=false，FR-009）
        return FlexDeletes.execWithoutLogicDelete(() -> mapper().selectListByQuery(
                QueryWrapper.create()
                        .where(new QueryColumn("deleted").eq(true))
                        .and(new QueryColumn("updated_at").lt(cutoff))));
    }

    @Override
    public int forceDeleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return forceDeleteByQuery(QueryWrapper.create().where(new QueryColumn("id").in(ids)));
    }

    @Override
    public Optional<SysFile> findActiveByObjectKey(String objectKey) {
        // 默认查询面（deleted=false + 租户过滤）；object_key 无唯一约束，取单行语义（幂等命中同键同租户）
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(new QueryColumn("object_key").eq(objectKey))));
    }

    @Override
    public long sumActiveSize() {
        // 无排序聚合 QueryWrapper（12.4.1 计数口径同型）；租户过滤 + deleted=false 随默认查询面注入
        Object total = mapper().selectObjectByQuery(QueryWrapper.create()
                .select(QueryMethods.sum("size").as("total_size")));
        return total instanceof Number number ? number.longValue() : 0L;
    }
}
