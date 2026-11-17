package com.acme.platform.infra.api.dto;

import java.time.Instant;

/**
 * 运行异常日志 B 端详情视图（REQ-20261117-061017 §API GET /admin/v1/infra/op-exceptions/{id}）：
 * 列表字段 + {@code stackSummary} 全文（收集侧截断后落库形态）。
 */
public record AdminOpExceptionDetailView(
        String id,
        String service,
        String traceId,
        String spanId,
        String tenantId,
        String operatorId,
        String method,
        String path,
        Integer statusCode,
        String exceptionType,
        String message,
        String stackSummary,
        Instant occurredAt) {
}
