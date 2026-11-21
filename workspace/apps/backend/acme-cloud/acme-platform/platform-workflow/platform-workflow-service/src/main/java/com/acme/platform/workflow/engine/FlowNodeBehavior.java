package com.acme.platform.workflow.engine;

/**
 * 节点行为 SPI（设计规格 §3.6 扩展点：Map&lt;nodeType, FlowNodeBehavior&gt; 注册表、Spring bean
 * 收集，BPMN 标准行为为内置实现、业务可注册覆盖）。
 * 触发语义：token 到达节点时由引擎驱动循环调用（wait state 行为不推进 token，仅落等待面）。
 */
public interface FlowNodeBehavior {

    /** 行为绑定的节点类型（注册表键）。 */
    com.acme.platform.workflow.bpmn.BpmnElementType type();

    /**
     * token 到达处理（ENTER 历史由引擎统一记录；行为负责推进/等待/终止语义）。
     */
    void onEnter(EngineRuntime runtime, com.acme.platform.workflow.domain.WfExecution execution);
}
