package com.acme.framework.gateway.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-gateway 错误码枚举（Q-D06：SYS-03 段，序号 0001 起，DP-008 定稿后回填；8.8 状态码映射）。
 * ROUTE_FAILED 超时场景由错误封装映射 504（8.8：502/504），缺省登记 502。
 */
public enum GatewayError implements ErrorCode, HttpStatusAware {

    /** 鉴权失败（FR-019，8.8：401）。 */
    AUTH_FAILED("SYS-03-0001", "未认证：网关验签失败", 401),

    /** 令牌过期（8.8：401）。 */
    TOKEN_EXPIRED("SYS-03-0002", "未认证：令牌已过期", 401),

    /** 限流拒绝（FR-022，8.8：429）。 */
    RATE_LIMITED("SYS-03-0003", "请求过于频繁，请稍后重试", 429),

    /** 路由失败（8.8：502/504）。 */
    ROUTE_FAILED("SYS-03-0004", "服务暂时不可用", 502),

    /** 请求不合法（8.8：400）。 */
    BAD_REQUEST("SYS-03-0005", "请求参数不合法", 400),

    /** 越端访问（FR-019 异常分支，8.8：403；5.1.1 示例列登记随实现说明）。 */
    CHANNEL_MISMATCH("SYS-03-0006", "端别与路由不匹配", 403);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    GatewayError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
