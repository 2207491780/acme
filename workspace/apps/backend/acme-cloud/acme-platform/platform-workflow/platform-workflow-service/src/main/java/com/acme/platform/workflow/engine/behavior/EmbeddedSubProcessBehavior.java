package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * EmbeddedSubProcess 行为：独立 scope（execution 子树 + 变量遮蔽——读穿透写不穿透）+
 * 挂接定时边界事件 + 驱动子流程 StartEvent（引擎 enterSubProcess 承载，§3.2）。
 */
@Component
public class EmbeddedSubProcessBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public EmbeddedSubProcessBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.EMBEDDED_SUB_PROCESS;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        operations.getObject().enterSubProcess(runtime, execution);
    }
}
