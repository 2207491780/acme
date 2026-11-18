package com.acme.platform.infra.api.dto;

import java.time.Instant;

/**
 * 审计事件 B 端视图（Backend.md 11.5.1 Admin* 前缀；字段集与 framework-audit AuditEvent 对齐 + 落库标识）。
 * 不可变 DTO、字段包装语义（9.2.1）；content 为脱敏后摘要（10.5.4）。
 */
public record AdminAuditEventView(
        String id,
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
        Instant createdAt) {
}
