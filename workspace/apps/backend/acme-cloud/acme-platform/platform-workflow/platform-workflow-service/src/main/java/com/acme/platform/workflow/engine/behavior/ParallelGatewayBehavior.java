package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ParallelGateway 行为（AND 分流/汇合终稿——设计规格 §2/§3.2）：fork 派生并发子执行；
 * join 到达计数聚合（最后一个到达者聚合继续，部分到达不推进——AC-4）。fork/join 形态由
 * 引擎按静态出入边数裁决（快照计数，禁动态加入）。
 */
@Component
public class ParallelGatewayBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public ParallelGatewayBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.PARALLEL_GATEWAY;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        operations.getObject().handleParallelGateway(runtime, execution);
    }
}
