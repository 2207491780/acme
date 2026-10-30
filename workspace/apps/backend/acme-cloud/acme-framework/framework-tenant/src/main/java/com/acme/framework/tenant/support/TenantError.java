package com.acme.framework.tenant.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-tenant 错误码枚举（Q-D06 同型续编：SYS-05 段，DP-008 定稿后回填）。
 */
public enum TenantError implements ErrorCode, HttpStatusAware {

    /** 无租户取值（FR-001 异常分支 REJECT 策略）。 */
    TENANT_REQUIRED("SYS-05-0001", "缺少租户上下文", 403),

    /** 租户不匹配（保留登记，5.1）。 */
    TENANT_MISMATCH("SYS-05-0002", "租户不匹配", 403),

    /** 跨租户审计失败（FR-004 异常分支 fail-closed）。 */
    AUDIT_FAILED("SYS-05-0003", "跨租户审计失败", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    TenantError(String code, String defaultMessage, int httpStatus) {
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
