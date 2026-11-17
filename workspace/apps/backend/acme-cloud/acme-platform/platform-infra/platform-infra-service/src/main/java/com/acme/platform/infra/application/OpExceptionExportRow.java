package com.acme.platform.infra.application;

import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelSheet;
import com.acme.platform.infra.domain.OpException;

/**
 * 运行异常日志导出行（REQ-20261117-061017 确认④：导出列 = 列表页列集；映射声明归业务侧 BR-010，
 * DemoRow 先例同型；时间以 ISO-8601 文本承载）。
 */
@ExcelSheet("op-exceptions")
public class OpExceptionExportRow {

    @ExcelField(value = "ID", order = 1)
    private String id;

    @ExcelField(value = "服务", order = 2)
    private String service;

    @ExcelField(value = "链路ID", order = 3)
    private String traceId;

    @ExcelField(value = "方法", order = 4)
    private String method;

    @ExcelField(value = "路径", order = 5)
    private String path;

    @ExcelField(value = "状态码", order = 6)
    private Integer statusCode;

    @ExcelField(value = "异常类型", order = 7)
    private String exceptionType;

    @ExcelField(value = "消息", order = 8)
    private String message;

    @ExcelField(value = "发生时间", order = 9)
    private String occurredAt;

    public OpExceptionExportRow() {
    }

    /** 实体 → 导出行（occurredAt ISO-8601 文本）。 */
    public static OpExceptionExportRow from(OpException exception) {
        OpExceptionExportRow row = new OpExceptionExportRow();
        row.id = exception.getId() == null ? null : String.valueOf(exception.getId());
        row.service = exception.getService();
        row.traceId = exception.getTraceId();
        row.method = exception.getMethod();
        row.path = exception.getPath();
        row.statusCode = exception.getStatusCode();
        row.exceptionType = exception.getExceptionType();
        row.message = exception.getMessage();
        row.occurredAt = exception.getOccurredAt() == null ? null : exception.getOccurredAt().toString();
        return row;
    }

    public String getId() {
        return id;
    }

    public String getService() {
        return service;
    }

    public String getTraceId() {
        return traceId;
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

    public String getOccurredAt() {
        return occurredAt;
    }
}
