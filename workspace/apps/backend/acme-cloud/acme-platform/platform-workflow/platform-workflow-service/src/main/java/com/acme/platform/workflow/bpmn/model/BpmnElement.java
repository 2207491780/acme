package com.acme.platform.workflow.bpmn.model;

import com.acme.platform.workflow.bpmn.BpmnElementType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BPMN 节点解析产物（DOM 展平；仅承载一期语义面——acme 扩展属性挂独立 namespace）。
 *
 * @param id          节点 id（process 内唯一）
 * @param name        节点名称（可空）
 * @param type        一期白名单类型
 * @param acmeAttrs   acme 扩展属性（localName → 原始值）
 * @param attachedTo  边界事件挂接节点 id（仅 BOUNDARY_TIMER 携带）
 * @param timerDelay  ISO-8601 时长（timeDuration，可空）
 * @param timerDate   ISO-8601 时刻（timeDate，可空）
 * @param defaultFlow default 流出 id（仅网关可携带）
 * @param containerId 宿主容器 id（顶层 process id 或子流程节点 id——start/end 恰一按容器校验）
 */
public record BpmnElement(String id, String name, BpmnElementType type, Map<String, String> acmeAttrs,
                          String attachedTo, String timerDelay, String timerDate, String defaultFlow,
                          String containerId) {

    public BpmnElement {
        acmeAttrs = acmeAttrs == null ? Map.of() : Map.copyOf(acmeAttrs);
    }

    /** acme 属性取值（缺失返回 null）。 */
    public String acme(String localName) {
        return acmeAttrs.get(localName);
    }
}
