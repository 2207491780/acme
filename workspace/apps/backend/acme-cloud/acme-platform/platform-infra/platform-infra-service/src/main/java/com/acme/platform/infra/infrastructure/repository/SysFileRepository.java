package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.SysFile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 平台文件仓库（租户列自动过滤；软删语义，清理路径走物理删除旁路）。 */
public interface SysFileRepository extends FlexRepository<SysFile> {

    /**
     * 查询软删且更新时间早于 cutoff 的记录（含已软删行，绕开逻辑删除过滤面；
     * 租户过滤随调用访问点声明——清理任务为平台级跨租户语义）。
     */
    List<SysFile> findSoftDeletedExpiredBefore(Instant cutoff);

    /** 按主键集合物理删除（软删超期清理闭环，AC-15）。 */
    int forceDeleteByIds(Collection<Long> ids);

    /**
     * 按对象键精确查未删行（直传 completion 幂等命中，REQ-20261117-045754 确认⑥；
     * 默认查询面：deleted=false + 租户过滤）。
     */
    Optional<SysFile> findActiveByObjectKey(String objectKey);

    /**
     * 未删行 size 总和（租户配额口径，REQ-20261117-045754 确认③；默认查询面租户过滤，
     * 无行返回 0；并发上传下为近似护栏非加锁值）。
     */
    long sumActiveSize();
}
