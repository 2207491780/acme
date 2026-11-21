package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.domain.WfVariable;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowVariableMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 流程变量仓储实现（MyBatis-Flex 底座；scope 前缀查询走 LIKE RIGHT 前缀面）。 */
@Repository("workflowVariableRepository")
public class WfVariableRepositoryImpl extends FlexRepositorySupport<WfVariable>
        implements WfVariableRepository {

    public WfVariableRepositoryImpl(WorkflowVariableMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<WfVariable> findByInstanceIdAndName(Long instanceId, String name) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(WfVariable::getInstanceId).eq(instanceId)
                .and(WfVariable::getName).eq(name)));
    }

    @Override
    public List<WfVariable> findByInstanceId(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfVariable::getInstanceId).eq(instanceId));
    }

    @Override
    public List<WfVariable> findByInstanceIdAndNamePrefix(Long instanceId, String prefix) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfVariable::getInstanceId).eq(instanceId)
                .and(WfVariable::getName).like(prefix + "%"));
    }
}
