package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowHistoryActivityMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 历史活动仓储实现（MyBatis-Flex 底座；start_at,id 双键稳定序）。 */
@Repository("workflowHistoryActivityRepository")
public class WfHistoryActivityRepositoryImpl extends FlexRepositorySupport<WfHistoryActivity>
        implements WfHistoryActivityRepository {

    public WfHistoryActivityRepositoryImpl(WorkflowHistoryActivityMapper mapper) {
        super(mapper);
    }

    @Override
    public List<WfHistoryActivity> findByInstanceIdOrdered(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfHistoryActivity::getInstanceId).eq(instanceId)
                .orderBy(WfHistoryActivity::getStartAt, true)
                .orderBy(WfHistoryActivity::getId, true));
    }
}
