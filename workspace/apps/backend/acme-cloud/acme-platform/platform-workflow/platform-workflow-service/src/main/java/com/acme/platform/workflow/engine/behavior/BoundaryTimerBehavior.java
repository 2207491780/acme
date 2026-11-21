package com.acme.platform.workflow.engine.behavior;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.FlowNodeBehavior;
import com.acme.platform.workflow.domain.WfExecution;
import org.springframework.stereotype.Component;

/**
 * 定时边界事件行为（中断型——设计规格 §2）：边界事件无入边（编译期校验），onEnter 不可达；
 * 触发面由 TimerFireCommand → ExecutionEngine.fireTimer 承载（CAS PENDING→FIRED + 中断挂接
 * 节点 + 边界流出分支推进，确认⑥）。注册本行为仅为行为注册表类型面完整（§3.6 SPI 键覆盖）。
 */
@Component
public class BoundaryTimerBehavior implements FlowNodeBehavior {

    @Override
    public BpmnElementType type() {
        return BpmnElementType.BOUNDARY_TIMER;
    }

    @Override
    public void onEnter(EngineRuntime runtime, WfExecution execution) {
        throw new IllegalStateException("边界事件不接受 token 到达（触发面归 TimerFireCommand）");
    }
}
