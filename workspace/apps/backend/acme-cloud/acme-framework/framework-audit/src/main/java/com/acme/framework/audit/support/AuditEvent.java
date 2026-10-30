package com.acme.framework.audit.support;

import java.time.Instant;

/**
 * 审计事件（FR-009/010、AC-008/009：事件内容完整性以字段集为准）。
 * 记录不含堆栈/SQL/内部地址（10.5.4、BR-013）；内容为脱敏后摘要。
 */
public record AuditEvent(
        String eventId,
        String traceId,
        String spanId,
        String tenantId,
        String operatorId,
        String channel,
        String action,
        String targetType,
        String targetId,
        String content,
        Instant occurredAt,
        String source) {
}
