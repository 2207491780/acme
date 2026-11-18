package com.acme.common.core;

/**
 * 通用系统错误码（DQ-002 复用口径；framework-core 阶段 3 按 BR-016 仅新增、Backend.md 7.2.1）。
 *
 * <p>字面量按 ErrorCodeLayout（Q-010）规范宽度：模块段 2 位、序号 4 位（G3a 确认）。</p>
 */
public enum CommonError implements ErrorCode {

    /** 目标不存在（DQ-002：组件不存在 / 必得配置缺键）。 */
    NOT_FOUND("SYS-00-0002", "请求的资源不存在"),

    /** 内部错误（DQ-002：应用上下文未就绪等）。 */
    INTERNAL_ERROR("SYS-00-0006", "系统内部错误");

    private final String code;
    private final String defaultMessage;

    CommonError(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
