package com.acme.platform.infra.application;

import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelSheet;
import com.acme.platform.infra.domain.OpAuditEvent;

/**
 * 审计事件导出行（REQ-20261117-061017 确认④：导出列 = 列表页列集 AdminAuditEventView 对齐；
 * 映射声明归业务侧 BR-010，DemoRow 先例同型；时间以 ISO-8601 文本承载）。
 */
@ExcelSheet("audit-events")
public class AuditEventExportRow {

    @ExcelField(value = "ID", order = 1)
    private String id;

    @ExcelField(value = "事件ID", order = 2)
    private String eventId;

    @ExcelField(value = "链路ID", order = 3)
    private String traceId;

    @ExcelField(value = "租户", order = 4)
    private String tenantId;

    @ExcelField(value = "操作人", order = 5)
    private String operatorId;

    @ExcelField(value = "端别", order = 6)
    private String channel;

    @ExcelField(value = "动作", order = 7)
    private String action;

    @ExcelField(value = "目标类型", order = 8)
    private String targetType;

    @ExcelField(value = "目标标识", order = 9)
    private String targetId;

    @ExcelField(value = "内容", order = 10)
    private String content;

    @ExcelField(value = "发生时间", order = 11)
    private String occurredAt;

    public AuditEventExportRow() {
    }

    /** 实体 → 导出行（occurredAt ISO-8601 文本）。 */
    public static AuditEventExportRow from(OpAuditEvent event) {
        AuditEventExportRow row = new AuditEventExportRow();
        row.id = event.getId() == null ? null : String.valueOf(event.getId());
        row.eventId = event.getEventId();
        row.traceId = event.getTraceId();
        row.tenantId = event.getTenantId();
        row.operatorId = event.getOperatorId();
        row.channel = event.getChannel();
        row.action = event.getAction();
        row.targetType = event.getTargetType();
        row.targetId = event.getTargetId();
        row.content = event.getContent();
        row.occurredAt = event.getOccurredAt() == null ? null : event.getOccurredAt().toString();
        return row;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTraceId() {
        return traceId;
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

    public String getOccurredAt() {
        return occurredAt;
    }
}
