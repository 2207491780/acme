package com.acme.framework.observability.support;

/**
 * 指标命名与标签口径（FR-019、14.2.1：统一可聚合）。
 * RED 指标统一前缀 {@code acme.red}；维度含服务、端别、资源、状态（架构设计 12.1）。
 */
public final class MetricNames {

    /** RED 请求计数（Rate/Error 共用计数器，status 维度区分）。 */
    public static final String RED_REQUESTS = "acme.red.requests";

    /** RED 耗时（Duration）。 */
    public static final String RED_DURATION = "acme.red.duration";

    /** 服务标签。 */
    public static final String TAG_SERVICE = "service";

    /** 端别标签（B/C）。 */
    public static final String TAG_CHANNEL = "channel";

    /** 资源标签。 */
    public static final String TAG_RESOURCE = "resource";

    /** 状态标签（success/error）。 */
    public static final String TAG_STATUS = "status";

    /** 成功状态取值。 */
    public static final String STATUS_SUCCESS = "success";

    /** 失败状态取值。 */
    public static final String STATUS_ERROR = "error";

    private MetricNames() {
    }
}
