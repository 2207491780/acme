package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.bpmn.BpmnElementType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 节点行为注册表（设计规格 §3.6：Spring bean 收集 → Map&lt;nodeType, FlowNodeBehavior&gt;；
 * 一期内置行为集：Start/End/SequenceFlow 取流语义并入引擎 / Exclusive / Parallel / UserTask /
 * ServiceTask / EmbeddedSubProcess / BoundaryTimer）。同类型多 bean 时后注册覆盖（业务覆盖挂点）。
 */
@Component
public class NodeBehaviorRegistry {

    private final Map<BpmnElementType, FlowNodeBehavior> behaviors = new EnumMap<>(BpmnElementType.class);

    public NodeBehaviorRegistry(List<FlowNodeBehavior> behaviorBeans) {
        for (FlowNodeBehavior behavior : behaviorBeans) {
            behaviors.put(behavior.type(), behavior);
        }
    }

    /** 行为定位（未注册类型抛 IllegalStateException——编译白名单与注册表一一对应）。 */
    public FlowNodeBehavior of(BpmnElementType type) {
        FlowNodeBehavior behavior = behaviors.get(type);
        if (behavior == null) {
            throw new IllegalStateException("未注册节点行为：" + type);
        }
        return behavior;
    }
}
