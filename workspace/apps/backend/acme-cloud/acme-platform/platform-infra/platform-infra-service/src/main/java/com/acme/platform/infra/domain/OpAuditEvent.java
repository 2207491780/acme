package com.acme.platform.infra.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 操作审计事件（p5 设计 18.2 列集定稿 + event_id 唯一键 M-12 幂等补强）：
 * 事件行不可变——无软删语义（deleted 恒 false）、不提供修改删除面；
 * 落库租户经消费管线恢复的 {@code TenantContext} 自动注入（12.4.1）。
 */
@Table("acme_op_audit")
public class OpAuditEvent extends AuditBase {

    /** 事件唯一标识（messageId 口径）；唯一约束兜底重复投递幂等（M-12 双保险）。 */
    @Column("event_id")
    private String eventId;

    @Column("trace_id")
    private String traceId;

    @Column("span_id")
    private String spanId;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("operator_id")
    private String operatorId;

    @Column("channel")
    private String channel;

    @Column("action")
    private String action;

    @Column("target_type")
    private String targetType;

    @Column("target_id")
    private String targetId;

    /** 脱敏后摘要（10.5.4：不含堆栈/SQL/内部地址）。 */
    @Column("content")
    private String content;

    @Column("occurred_at")
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
