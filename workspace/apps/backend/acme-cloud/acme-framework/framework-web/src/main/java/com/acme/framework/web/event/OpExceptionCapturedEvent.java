package com.acme.framework.web.event;

import java.time.Instant;

/**
 * 运行异常捕获事件（REQ-20261117-061017 确认①）：GlobalExceptionTranslator 仅在系统异常 500 路径
 * （兜底 Exception 与 DataAccessException 还原失败的 500）发布；BizException/参数校验类 4xx 不发
 * （业务失败非系统异常）。堆栈摘要在发布侧截断 8KB；上下文（traceId/tenantId/operatorId）在请求线程
 * 捕获后随事件走——消费侧异步线程不再持有上下文。
 *
 * @param service       来源服务标识（spring.application.name）
 * @param traceId       链路 traceId（无上下文为 null）
 * @param spanId        链路 spanId（无上下文为 null）
 * @param tenantId      租户 id（无上下文为 null，消费侧归一）
 * @param operatorId    操作人 id（无上下文为 null）
 * @param method        HTTP 方法（非 Web 语境为 null）
 * @param path          请求路径（非 Web 语境为 null）
 * @param statusCode    响应状态码（本事件恒 500）
 * @param exceptionType 异常类型全名
 * @param message       异常消息
 * @param stackSummary  堆栈摘要（发布侧截断 8KB）
 * @param occurredAt    发生时间
 */
public record OpExceptionCapturedEvent(
        String service,
        String traceId,
        String spanId,
        String tenantId,
        String operatorId,
        String method,
        String path,
        int statusCode,
        String exceptionType,
        String message,
        String stackSummary,
        Instant occurredAt) {
}
