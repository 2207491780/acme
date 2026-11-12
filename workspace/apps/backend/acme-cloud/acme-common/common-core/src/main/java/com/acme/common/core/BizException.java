package com.acme.common.core;

/**
 * 业务异常基类（FR-016；BR-018、Backend.md 10.5.1）。携带错误码与面向调用方的提示；
 * 不得以异常作业务分支控制流；对外不得携带堆栈/SQL/内部地址（10.5.4，由接口层转换保证，本类仅承载）。
 *
 * <p>失败构造口径（DP-007 定稿）：errorCode 为 null → IllegalArgumentException（在父类构造前校验，
 * 不产生半构造异常对象）；message 为 null 时取 errorCode.defaultMessage()。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(resolveMessage(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    /** 错误码（FR-016）。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 校验并解析提示文案（在父类构造执行前完成，DP-007 定稿）。 */
    private static String resolveMessage(ErrorCode errorCode, String message) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        return message == null ? errorCode.defaultMessage() : message;
    }
}
