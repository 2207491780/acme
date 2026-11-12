package com.acme.framework.gateway.internal;

/**
 * 过滤链交接属性键（8.1 过滤链顺序内交接，internal）。
 */
public final class ExchangeAttrs {

    /** 已认证用户声明（AuthFilter 写入）。 */
    public static final String USER_CLAIMS = "com.acme.gateway.USER_CLAIMS";

    /** 净化后的设备标识（StripSpoofedHeadersFilter 写入）。 */
    public static final String DEVICE_ID = "com.acme.gateway.DEVICE_ID";

    /** 净化后的客户端版本（StripSpoofedHeadersFilter 写入）。 */
    public static final String APP_VERSION = "com.acme.gateway.APP_VERSION";

    /** Trace 上下文（TraceWebFilter 写入）。 */
    public static final String TRACE_CONTEXT = "com.acme.gateway.TRACE_CONTEXT";

    /** 请求开始时间（RequestLogFilter 用）。 */
    public static final String START_TIME = "com.acme.gateway.START_TIME";

    private ExchangeAttrs() {
    }
}
