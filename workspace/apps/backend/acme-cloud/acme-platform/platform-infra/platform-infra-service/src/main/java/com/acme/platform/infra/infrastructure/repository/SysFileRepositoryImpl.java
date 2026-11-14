package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.mapper.SysFileMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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
}
