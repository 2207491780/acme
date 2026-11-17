package com.acme.platform.infra.api.dto;

import java.time.Instant;

/**
 * 运行异常日志 B 端列表视图（REQ-20261117-061017 §API GET /admin/v1/infra/op-exceptions 行形态；
 * 列集 = sys_op_exception 业务列 + wire Long→string id，AdminAuditEventView 同型）。
 * 堆栈摘要不在列表行（详情视图 {@link AdminOpExceptionDetailView} 承载全文）。
 *
 * @param id            日志 id（雪花，字符串）
 * @param service       来源服务标识
 * @param traceId       链路 traceId
 * @param spanId        链路 spanId
 * @param tenantId      租户 id
 * @param operatorId    操作人 id
 * @param method        HTTP 方法
 * @param path          请求路径
 * @param statusCode    响应状态码
 * @param exceptionType 异常类型全名
 * @param message       异常消息
 * @param occurredAt    发生时间
 */
public record AdminOpExceptionView(
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
        Instant occurredAt) {
}
