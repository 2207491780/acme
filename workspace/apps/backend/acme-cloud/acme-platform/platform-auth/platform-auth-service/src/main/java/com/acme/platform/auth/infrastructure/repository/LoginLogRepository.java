package com.acme.platform.auth.infrastructure.repository;

import com.acme.common.contract.PageResult;
import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.auth.domain.SysLoginLog;

import java.time.Instant;

/** 登录日志仓库（仅插入 + 管理端分页筛选查询；写入收敛异步记录器）。 */
public interface LoginLogRepository extends FlexRepository<SysLoginLog> {

    /**
     * 分页筛选查询（username 包含匹配 / userType / result 精确 / created_at 时间区间；按创建时间倒序）。
     *
     * @param page      页码（1 起）
     * @param size      页大小
     * @param username  用户名筛选（包含匹配；null/空白忽略）
     * @param userType  端别筛选（null/空白忽略）
     * @param result    结果筛选（null/空白忽略）
     * @param startTime 时间区间下界（含；null 忽略）
     * @param endTime   时间区间上界（含；null 忽略）
     * @return 分页结果
     */
    PageResult<SysLoginLog> page(Integer page, Integer size, String username, String userType,
                                 String result, Instant from, Instant to);

    /**
     * 保留期物理清理（REQ-20261114-120105 AC-7；TL 裁决 C 方案收敛本服务）：
     * 物理删除软删（deleted=true）且 created_at 早于 cutoff 的日志行；
     * 未软删行与未超期软删行不受影响（幂等，可重复调用）。
     *
     * @return 本次物理清理条数
     */
    int forceDeleteSoftDeletedExpiredBefore(Instant cutoff);
}
