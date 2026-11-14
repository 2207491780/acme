package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.SysFile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 平台文件仓库（租户列自动过滤；软删语义，清理路径走物理删除旁路）。 */
public interface SysFileRepository extends FlexRepository<SysFile> {

    /**
     * 查询软删且更新时间早于 cutoff 的记录（含已软删行，绕开逻辑删除过滤面；
     * 租户过滤随调用访问点声明——清理任务为平台级跨租户语义）。
     */
    List<SysFile> findSoftDeletedExpiredBefore(Instant cutoff);

    /** 按主键集合物理删除（软删超期清理闭环，AC-15）。 */
    int forceDeleteByIds(Collection<Long> ids);
}
