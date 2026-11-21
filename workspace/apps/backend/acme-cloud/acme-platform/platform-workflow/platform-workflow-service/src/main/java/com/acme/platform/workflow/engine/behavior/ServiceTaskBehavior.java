package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ServiceTask 行为（wait state）：HTTP 委托下发（首发 attempt=0）——确认⑤：回调成功推进、
 * 失败按 acme:retryMaxAttempts 重试后挂起实例（reason=SERVICE_TASK_FAILED）。token 停驻等待
 * internal callback 推进。
 */
@Component
public class ServiceTaskBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public ServiceTaskBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.SERVICE_TASK;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        operations.getObject().dispatchServiceTask(runtime, execution, 0);
    }
}
