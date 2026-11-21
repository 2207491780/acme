package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * EndEvent 行为（none 类型）：容器级语义——process 容器到达即实例 COMPLETED；子流程容器到达
 * 即回收子树后自外层 execution 继续（引擎 handleEndEvent 承载）。
 */
@Component
public class EndEventBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public EndEventBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.END_EVENT;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        operations.getObject().handleEndEvent(runtime, execution);
    }
}
