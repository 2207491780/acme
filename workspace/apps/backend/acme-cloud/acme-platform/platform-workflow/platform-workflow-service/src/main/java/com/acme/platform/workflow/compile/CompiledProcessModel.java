package com.acme.platform.workflow.compile;

import java.util.List;
import java.util.Map;

/**
 * 编译流程模型（部署时 XML→模型一次成型，ConcurrentHashMap 缓存；运行时零 XML 解析——
 * 设计规格 §3.4）：元素映射 + 流出表 + 行为绑定 + 表达式预解析 AST。
 */
public final class CompiledProcessModel {

    /** 定义 id（缓存键）。 */
    private final Long definitionId;

    /** process key。 */
    private final String processKey;

    /** 节点映射（id → 编译节点）。 */
    private final Map<String, CompiledElement> elements;

    /** 连线映射（id → 编译连线）。 */
    private final Map<String, CompiledFlow> flows;

    /** 实例启动节点（StartEvent id）。 */
    private final String startElementId;

    public CompiledProcessModel(Long definitionId, String processKey, Map<String, CompiledElement> elements,
                                Map<String, CompiledFlow> flows, String startElementId) {
        this.definitionId = definitionId;
        this.processKey = processKey;
        this.elements = Map.copyOf(elements);
        this.flows = Map.copyOf(flows);
        this.startElementId = startElementId;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public String getProcessKey() {
        return processKey;
    }

    public Map<String, CompiledElement> getElements() {
        return elements;
    }

    /** 节点定位（不存在抛 IllegalArgumentException——运行时节点 id 来自已编译表，防御式）。 */
    public CompiledElement element(String id) {
        CompiledElement element = elements.get(id);
        if (element == null) {
            throw new IllegalArgumentException("编译模型缺失节点：" + id);
        }
        return element;
    }

    public CompiledFlow flow(String id) {
        CompiledFlow flow = flows.get(id);
        if (flow == null) {
            throw new IllegalArgumentException("编译模型缺失连线：" + id);
        }
        return flow;
    }

    public Map<String, CompiledFlow> getFlows() {
        return flows;
    }

    public String getStartElementId() {
        return startElementId;
    }

    /** 节点流出连线全集（编译序）。 */
    public List<CompiledFlow> outgoingOf(String nodeId) {
        return element(nodeId).outgoing().stream().map(this::flow).toList();
    }

    /** 节点流入连线全集。 */
    public List<CompiledFlow> incomingOf(String nodeId) {
        return element(nodeId).incoming().stream().map(this::flow).toList();
    }

    /** 节点挂接的定时边界事件（USER_TASK/EMBEDDED_SUB_PROCESS 面）。 */
    public List<CompiledElement> attachedTimersOf(String nodeId) {
        return element(nodeId).attachedTimers().stream().map(this::element).toList();
    }
}
