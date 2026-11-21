package com.acme.platform.workflow.bpmn.model;

/**
 * BPMN 连线解析产物（SequenceFlow 展平；conditionExpression 原文，编译期预解析）。
 *
 * @param id                  连线 id
 * @param name                连线名称
 * @param sourceRef           源节点 id
 * @param targetRef           目标节点 id
 * @param conditionExpression 条件表达式原文（可空；${…} 包裹或裸 SpEL）
 * @param defaultFlow         源网关 default 指向本连线
 */
public record BpmnFlow(String id, String name, String sourceRef, String targetRef,
                       String conditionExpression, boolean defaultFlow) {
}
