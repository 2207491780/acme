package com.acme.platform.workflow.compile;

import org.springframework.expression.Expression;

/**
 * 编译后连线（部署时产物）：条件表达式已预解析为 SpEL AST（设计规格 §3.4 编译缓存承载）。
 *
 * @param id              连线 id
 * @param sourceRef       源节点 id
 * @param targetRef       目标节点 id
 * @param condition       预解析条件表达式（无条件=null）
 * @param defaultFlow     源网关 default 指向本连线
 */
public record CompiledFlow(String id, String sourceRef, String targetRef,
                           Expression condition, boolean defaultFlow) {
}
