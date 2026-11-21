package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.compile.CompiledFlow;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.expression.WorkflowExpressionEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ExclusiveGateway 行为（XOR 分流终稿——设计规格 §2）：取首个条件为真的流出（编译流出表序）；
 * 无真走 default flow；无真无 default → 引擎挂起该 execution（no-route）。
 * 条件求值主体=实例发起人（WorkflowExpressionEvaluator 协议终稿）。
 */
@Component
public class ExclusiveGatewayBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;
    private final WorkflowExpressionEvaluator expressionEvaluator;

    public ExclusiveGatewayBehavior(ObjectProvider<EngineOperations> operations,
                                    WorkflowExpressionEvaluator expressionEvaluator) {
        this.operations = operations;
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.EXCLUSIVE_GATEWAY;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        var outgoing = runtime.model().outgoingOf(execution.getNodeId());
        String subject = runtime.instance().getInitiator();
        CompiledFlow chosen = null;
        for (CompiledFlow flow : outgoing) {
            if (flow.condition() != null && expressionEvaluator.evaluateBoolean(
                    flow.condition(), runtime.variablesFor(execution), subject)) {
                chosen = flow;
                break;
            }
        }
        if (chosen == null) {
            chosen = outgoing.stream().filter(CompiledFlow::defaultFlow).findFirst().orElse(null);
        }
        if (chosen == null) {
            operations.getObject().deadEndNoRoute(runtime, execution,
                    "exclusive-gateway-no-route:" + execution.getNodeId());
            return;
        }
        operations.getObject().takeFlow(runtime, execution, chosen);
    }
}
