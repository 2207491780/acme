package com.acme.framework.audit.support;

/**
 * 审计事件出口 SPI（FR-009、Q-005：本期唯一默认通道 = Spring 事件广播；落库与查询归 platform-infra 后续特性）。
 */
public interface AuditEventPublisher {

    /** 发布审计事件（埋点失败不阻断业务，BR-013——失败处置在调用方兜底）。 */
    void publish(AuditEvent event);
}
