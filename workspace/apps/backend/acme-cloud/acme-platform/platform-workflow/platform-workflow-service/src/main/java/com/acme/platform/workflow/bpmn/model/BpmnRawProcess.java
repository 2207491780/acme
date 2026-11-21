package com.acme.platform.workflow.bpmn.model;

import java.util.List;

/**
 * BPMN process 解析产物（编译模型输入；XML→模型展开与结构校验分离，设计规格 §3.4）。
 *
 * @param id       process id
 * @param name     process name
 * @param elements 节点全集（含子流程内节点与边界事件）
 * @param flows    连线全集
 */
public record BpmnRawProcess(String id, String name, List<BpmnElement> elements, List<BpmnFlow> flows) {
}
