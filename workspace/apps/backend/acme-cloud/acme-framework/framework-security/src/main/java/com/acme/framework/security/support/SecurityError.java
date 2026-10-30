package com.acme.framework.security.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-security 错误码枚举（Q-D06：SYS-02 段，序号 0001 起，DP-008 定稿后回填）。
 */
public enum SecurityError implements ErrorCode, HttpStatusAware {

    /** 内部令牌缺失（FR-014，7.2：401）。 */
    TOKEN_MISSING("SYS-02-0001", "未认证：缺少内部令牌", 401),

    /** 内部令牌无效（7.2：401）。 */
    TOKEN_INVALID("SYS-02-0002", "未认证：内部令牌无效", 401),

    /** 内部令牌过期（7.2：401）。 */
    TOKEN_EXPIRED("SYS-02-0003", "未认证：内部令牌已过期", 401),

    /** 令牌类型不匹配（FR-015，7.3：403）。 */
    TOKEN_TYPE_MISMATCH("SYS-02-0004", "令牌类型不匹配", 403),

    /** 端别声明不匹配（FR-015，7.3：403）。 */
    CHANNEL_MISMATCH("SYS-02-0005", "端别声明与令牌不匹配", 403),

    /** 未授权（FR-016 异常分支：401）。 */
    UNAUTHORIZED("SYS-02-0006", "未授权", 401),

    /** 数据权限拒绝（FR-017，7.5：403）。 */
    DATA_PERMISSION_DENIED("SYS-02-0007", "无权访问目标数据", 403);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    SecurityError(String code, String defaultMessage, int httpStatus) {
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
