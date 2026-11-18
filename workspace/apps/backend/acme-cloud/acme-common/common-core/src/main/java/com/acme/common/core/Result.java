package com.acme.common.core;

/**
 * 统一出参包装（FR-013；Backend.md 7.1.1）。字段用包装类型（Backend.md 9.2.1）。
 *
 * <p>口径（DP-007 定稿）：成功码取 {@code "0"}（成功不占错误码段位，消费方以
 * {@link #getSuccess()} 判定成败，code 仅作信息性标识）；失败构造缺错误码显式失败。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class Result<T> {

    /** 成功码（DP-007 定稿）：非错误码段位，表示无错误。 */
    private static final String SUCCESS_CODE = "0";

    private final Boolean success;
    private final String code;
    private final String message;
    private final T data;

    private Result(Boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功出参（FR-013）。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(Boolean.TRUE, SUCCESS_CODE, null, data);
    }

    /** 失败出参（FR-013）；缺少错误码显式失败（DP-007 定稿：IllegalArgumentException）。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    /** 失败出参（FR-013）；message 为空时取 errorCode.defaultMessage()（Backend.md 10.5.1）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    /**
     * 带结构化明细的失败出参（Q-D05）：明细放 data（如 {@link FieldErrorDetail} 列表）；
     * 既有 {@link #fail(ErrorCode)} / {@link #fail(ErrorCode, String)} 行为不变。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        String finalMessage = message == null ? errorCode.defaultMessage() : message;
        return new Result<>(Boolean.FALSE, errorCode.code(), finalMessage, data);
    }

    /** true=成功（FR-013）。 */
    public Boolean getSuccess() {
        return success;
    }

    /** 成功时取成功码 {@code "0"}，失败时为错误码（FR-013；DP-007 定稿）。 */
    public String getCode() {
        return code;
    }

    /** 面向调用方的提示（FR-013；Backend.md 10.5.1）。 */
    public String getMessage() {
        return message;
    }

    /** 业务数据；失败时为 null（FR-013）。 */
    public T getData() {
        return data;
    }
}
