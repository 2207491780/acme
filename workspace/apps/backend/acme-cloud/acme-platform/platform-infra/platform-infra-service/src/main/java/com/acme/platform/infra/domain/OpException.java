package com.acme.platform.infra.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 运行异常日志（REQ-20261117-061017 §变更清单 sys_op_exception：service/trace_id/span_id/tenant_id/
 * operator_id/method/path/status_code/exception_type/message/stack_summary/occurred_at + 审计基类口径）。
 * 来源 = framework-web OpExceptionCapturedEvent 异步收集（确认①）；软删列保留（保留期清理任务按
 * 「软删超期物理清理」范式执行）；租户列 @TenantId 自动过滤（12.4.1）。
 */
@Table("sys_op_exception")
public class OpException extends AuditBase {

    @Column("service")
    private String service;

    @Column("trace_id")
    private String traceId;

    @Column("span_id")
    private String spanId;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("operator_id")
    private String operatorId;

    @Column("method")
    private String method;

    @Column("path")
    private String path;

    @Column("status_code")
    private Integer statusCode;

    @Column("exception_type")
    private String exceptionType;

    /** 异常消息（收集侧截断至列宽 1024）。 */
    @Column("message")
    private String message;

    /** 堆栈摘要（收集侧截断，落库全文口径）。 */
    @Column("stack_summary")
    private String stackSummary;

    @Column("occurred_at")
    private Instant occurredAt;

    protected OpException() {
    }

    public OpException(String service, String traceId, String spanId, String operatorId,
                       String method, String path, Integer statusCode, String exceptionType,
                       String message, String stackSummary, Instant occurredAt) {
        this.service = service;
        this.traceId = traceId;
        this.spanId = spanId;
        this.operatorId = operatorId;
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.exceptionType = exceptionType;
        this.message = message;
        this.stackSummary = stackSummary;
        this.occurredAt = occurredAt;
    }

    public String getService() {
        return service;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    public String getStackSummary() {
        return stackSummary;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
