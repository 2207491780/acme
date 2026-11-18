package com.acme.framework.web.internal;

import com.acme.common.contract.TraceConstants;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 服务侧访问日志输出辅助（FR-006；logger 名 {@code acme.access}；要素 14.1.1、5.5）。
 * 日志失败不影响请求处理（FR-006 异常分支，调用方 try/catch）。
 */
public final class AccessLogger {

    /** 访问日志 logger 名（5.1）。 */
    public static final String LOGGER_NAME = "acme.access";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private AccessLogger() {
    }

    /** 输出一行访问日志（FR-006）；要素：traceId/service/spanId + method/path/status/耗时/channel/deviceId/userId。 */
    public static void log(String serviceName, HttpServletRequest request, int status, long durationMillis,
                           ClientContextDraft draft, AuthenticatedSubject subject) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("access service={} traceId={} spanId={} method={} path={} status={} durationMs={} channel={} deviceId={} userId={}",
                serviceName,
                MDC.get(TraceConstants.MDC_TRACE_ID),
                MDC.get(TraceConstants.MDC_SPAN_ID),
                request.getMethod(),
                request.getRequestURI(),
                status,
                durationMillis,
                draft == null ? null : draft.channel(),
                draft == null ? null : draft.deviceId(),
                subject == null ? null : subject.userId());
    }
}
