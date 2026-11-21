package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.domain.WfHistoryActivity;

import java.util.List;

/** 历史活动仓储（AC-15 历史链按序查询面）。 */
public interface WfHistoryActivityRepository extends FlexRepository<WfHistoryActivity> {

    /** 实例历史链（start_at,id 双键序——同刻多行保稳定序）。 */
    List<WfHistoryActivity> findByInstanceIdOrdered(Long instanceId);
}
