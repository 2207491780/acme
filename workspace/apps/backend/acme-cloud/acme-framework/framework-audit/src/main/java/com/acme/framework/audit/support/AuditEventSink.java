package com.acme.framework.audit.support;

/**
 * 审计事件下游桥接 SPI（FR-009、架构设计 8.4）：供后续 MQ/落库桥接（归 platform-infra 后续特性）；
 * 本期不内置 MQ 桥（audit 不得依赖 stream，Modules.md 4.3 横向禁令）。
 */
public interface AuditEventSink {

    /** 接收审计事件（实现失败不得影响业务主流程）。 */
    void onEvent(AuditEvent event);
}
