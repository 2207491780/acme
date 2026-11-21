package com.acme.platform.workflow.compile;

import com.acme.platform.workflow.bpmn.BpmnElementType;

import java.util.List;
import java.util.Map;

/**
 * 编译后节点（部署时产物，运行时零解析面——设计规格 §3.4）：元素元数据 + 流出表 + 行为绑定。
 *
 * @param id             节点 id
 * @param name           节点名称
 * @param type           一期白名单类型（行为注册表键）
 * @param acmeAttrs      acme 扩展属性
 * @param outgoing       流出连线 id（编译序）
 * @param incoming       流入连线 id
 * @param attachedTimers 挂接本节点的定时边界事件 id（USER_TASK/EMBEDDED_SUB_PROCESS）
 * @param attachedTo     边界事件挂接目标节点 id（BOUNDARY_TIMER）
 * @param timerDelayMs   定时时长毫秒（timeDuration 展平；null=非时长语义）
 * @param timerDate      定时绝对时刻（timeDate 原文；null=非时刻语义）
 * @param defaultFlow    default 流出连线 id（网关）
 * @param containerId    宿主容器 id（顶层 process 或子流程节点——子流程 StartEvent 定位键）
 * @param behaviors      行为绑定标记（内置行为集键，预留业务覆盖挂点 §3.6）
 */
public record CompiledElement(String id, String name, BpmnElementType type, Map<String, String> acmeAttrs,
                              List<String> outgoing, List<String> incoming, List<String> attachedTimers,
                              String attachedTo, Long timerDelayMs, String timerDate, String defaultFlow,
                              String containerId, String behaviorKey) {

    /** acme 属性取值（缺失返回 null）。 */
    public String acme(String localName) {
        return acmeAttrs.get(localName);
    }
}
