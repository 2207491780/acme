package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * StartEvent 行为（none 类型，每容器恰一）：无等待面，token 立即沿唯一流出推进（编译期校验
 * 出边恰一形态——多出边按流出表顺序取首条）。
 */
@Component
public class StartEventBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public StartEventBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.START_EVENT;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        var outgoing = runtime.model().outgoingOf(execution.getNodeId());
        operations.getObject().takeFlow(runtime, execution, outgoing.get(0));
    }
}
