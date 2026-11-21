package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineOperations;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * UserTask 行为（wait state）：指派规则解析 → 任务创建（CREATED，候选语义 ALL）+ 挂接定时
 * 边界事件；无候选人 → 实例 NO_ASSIGNEE_RESOLVED 挂起（AC-5）。token 停驻等待 complete/reject
 * 等操作命令推进。
 */
@Component
public class UserTaskBehavior implements FlowNodeBehavior {

    private final ObjectProvider<EngineOperations> operations;

    public UserTaskBehavior(ObjectProvider<EngineOperations> operations) {
        this.operations = operations;
    }

    @Override
    public BpmnElementType type() {
        return BpmnElementType.USER_TASK;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        operations.getObject().createUserTask(runtime, execution);
    }
}
