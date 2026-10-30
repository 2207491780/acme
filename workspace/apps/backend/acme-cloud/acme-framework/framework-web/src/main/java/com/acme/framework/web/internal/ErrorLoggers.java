package com.acme.framework.web.internal;

import com.acme.common.contract.TraceConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 异常日志输出辅助（10.5.4、14.1.1；logger 名 {@code acme.error}）：带 traceId 可关联，不外泄实现细节。
 */
public final class ErrorLoggers {

    /** 未知异常日志 logger 名（5.1）。 */
    public static final String LOGGER_NAME = "acme.error";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private ErrorLoggers() {
    }

    /** 未知异常 ERROR 日志（5.2 映射表末行）；traceId 取 MDC（14.1.1）。 */
    public static void logUnexpected(Throwable ex) {
        log.error("未处理异常 traceId={} type={}", MDC.get(TraceConstants.MDC_TRACE_ID),
                ex.getClass().getName(), ex);
    }
}
