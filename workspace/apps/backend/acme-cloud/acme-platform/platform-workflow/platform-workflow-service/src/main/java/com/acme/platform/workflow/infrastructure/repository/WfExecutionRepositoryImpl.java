package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowExecutionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 执行树仓储实现（MyBatis-Flex 底座；token 查询均为等值面）。 */
@Repository("workflowExecutionRepository")
public class WfExecutionRepositoryImpl extends FlexRepositorySupport<WfExecution>
        implements WfExecutionRepository {

    public WfExecutionRepositoryImpl(WorkflowExecutionMapper mapper) {
        super(mapper);
    }

    @Override
    public List<WfExecution> findByInstanceId(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfExecution::getInstanceId).eq(instanceId));
    }

    @Override
    public List<WfExecution> findActiveByInstanceId(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfExecution::getInstanceId).eq(instanceId)
                .and(WfExecution::getActive).eq(true));
    }

    @Override
    public List<WfExecution> findActiveByInstanceIdAndNodeId(Long instanceId, String nodeId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfExecution::getInstanceId).eq(instanceId)
                .and(WfExecution::getActive).eq(true)
                .and(WfExecution::getNodeId).eq(nodeId));
    }

    @Override
    public List<WfExecution> findActiveByInstanceIdAndNodeIdForUpdate(Long instanceId, String nodeId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfExecution::getInstanceId).eq(instanceId)
                .and(WfExecution::getActive).eq(true)
                .and(WfExecution::getNodeId).eq(nodeId)
                .forUpdate());
    }

    @Override
    public List<WfExecution> findByParentId(Long parentId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfExecution::getParentId).eq(parentId));
    }

    @Override
    @com.acme.framework.tenant.support.IgnoreTenant("workflow-engine-execution-lookup")
    public java.util.Optional<WfExecution> findByIdGlobal(Long id) {
        return findById(id);
    }
}
