package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.domain.WfVariable;

import java.util.List;
import java.util.Optional;

/** 流程变量仓储（uk(instance_id,name) 读写面；scope 遮蔽以前缀查询承载）。 */
public interface WfVariableRepository extends FlexRepository<WfVariable> {

    /** 按名精确定位（scope 限定名由 VariableScope 组合）。 */
    Optional<WfVariable> findByInstanceIdAndName(Long instanceId, String name);

    /** 实例变量全集（详情摘要/委托快照）。 */
    List<WfVariable> findByInstanceId(Long instanceId);

    /** scope 限定名前缀查询（子流程结束回收：name LIKE 'prefix%'）。 */
    List<WfVariable> findByInstanceIdAndNamePrefix(Long instanceId, String prefix);
}
