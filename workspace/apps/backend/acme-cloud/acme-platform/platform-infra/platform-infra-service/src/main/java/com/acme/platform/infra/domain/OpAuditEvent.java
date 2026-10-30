package com.acme.platform.infra.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 操作审计事件（p5 设计 18.2 列集定稿 + event_id 唯一键 M-12 幂等补强）：
 * 事件行不可变——无软删语义（deleted 恒 false）、不提供修改删除面；
 * 落库租户经消费管线恢复的 {@code TenantContext} 自动注入（12.4.1）。
 */
@Entity
@Table(name = "acme_op_audit",
        uniqueConstraints = @UniqueConstraint(name = "uk_infra_audit_event", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_infra_audit_tenant_time", columnList = "tenant_id, occurred_at"),
                @Index(name = "idx_infra_audit_trace", columnList = "trace_id")
        })
public class OpAuditEvent extends AuditBase {

    /** 事件唯一标识（messageId 口径）；唯一约束兜底重复投递幂等（M-12 双保险）。 */
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "span_id", length = 64)
    private String spanId;

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "operator_id", length = 64)
    private String operatorId;

    @Column(name = "channel", length = 8)
    private String channel;

    @Column(name = "action", length = 128, nullable = false)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    /** 脱敏后摘要（10.5.4：不含堆栈/SQL/内部地址）。 */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OpAuditEvent() {
    }

    public OpAuditEvent(String eventId, String traceId, String spanId,
                        String operatorId, String channel, String action,
                        String targetType, String targetId, String content, Instant occurredAt) {
        this.eventId = eventId;
        this.traceId = traceId;
        this.spanId = spanId;
        this.operatorId = operatorId;
        this.channel = channel;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.content = content;
        this.occurredAt = occurredAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getChannel() {
        return channel;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getContent() {
        return content;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
