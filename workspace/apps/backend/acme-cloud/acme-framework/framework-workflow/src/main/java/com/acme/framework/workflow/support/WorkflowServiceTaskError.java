package com.acme.framework.workflow.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-workflow 错误码枚举（10.5.2 三段式，framework 段 SYS-13 续编；Q-D06 同型）。
 */
public enum WorkflowServiceTaskError implements ErrorCode, HttpStatusAware {

    /** serviceRef 未注册 handler（接收端点路由未命中，404 语义明确——AC-1）。 */
    HANDLER_NOT_FOUND("SYS-13-0001", "ServiceTask 未注册处理器", 404),

    /** 引擎回调失败（接收端点处理完成后自动回调 internal callback 失败，500——委托语义交回引擎重试）。 */
    CALLBACK_FAILED("SYS-13-0002", "ServiceTask 回调引擎失败", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    WorkflowServiceTaskError(String code, String defaultMessage, int httpStatus) {
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
