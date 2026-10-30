package com.acme.platform.infra;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.infra.api.InfraErrorCodes;

/**
 * 基础设施域错误码枚举（10.5.2 三段式，serviceId=platform-infra）。
 * 码值与 platform-infra-api InfraErrorCodes 常量一一对应。
 */
public enum InfraError implements ErrorCode, HttpStatusAware {

    /** 审计事件无效（协议字段缺失/非法；消费侧毒消息重试后转死信）。 */
    AUDIT_EVENT_INVALID(InfraErrorCodes.AUDIT_EVENT_INVALID, "审计事件无效", 400),

    /** 审计存储失败（落库异常；重试后转死信）。 */
    AUDIT_STORE_FAILED(InfraErrorCodes.AUDIT_STORE_FAILED, "审计存储失败", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    InfraError(String code, String defaultMessage, int httpStatus) {
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
