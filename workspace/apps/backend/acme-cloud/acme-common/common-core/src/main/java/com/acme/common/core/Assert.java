package com.acme.common.core;

import java.util.Collection;
import java.util.Objects;

/**
 * 条件断言（FR-017）。断言失败即抛 {@link BizException}，不返回 false、不静默通过。
 *
 * <p>断言项清单（DP-007 定稿）：notNull / notBlank / notEmpty(CharSequence) /
 * notEmpty(Collection) / noNullElements / isTrue / isFalse / state；
 * 后续断言项以兼容方式新增（Backend.md 7.2.1）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class Assert {

    private Assert() {
    }

    /** 非空断言（FR-017）；失败抛 {@link BizException}。 */
    public static void notNull(Object value, ErrorCode errorCode) {
        if (value == null) {
            throw new BizException(errorCode);
        }
    }

    /** 非空白断言（null 或全空白失败，DP-007 定稿）。 */
    public static void notBlank(CharSequence value, ErrorCode errorCode) {
        if (value == null || value.chars().allMatch(Character::isWhitespace)) {
            throw new BizException(errorCode);
        }
    }

    /** 非空字符串断言（null 或长度 0 失败，DP-007 定稿）。 */
    public static void notEmpty(CharSequence value, ErrorCode errorCode) {
        if (value == null || value.isEmpty()) {
            throw new BizException(errorCode);
        }
    }

    /** 非空集合断言（null 或空集合失败，DP-007 定稿）。 */
    public static void notEmpty(Collection<?> value, ErrorCode errorCode) {
        if (value == null || value.isEmpty()) {
            throw new BizException(errorCode);
        }
    }

    /** 集合不含 null 元素断言（null 集合或含 null 元素失败；空集合通过，DP-007 定稿）。 */
    public static void noNullElements(Collection<?> value, ErrorCode errorCode) {
        if (value == null || value.stream().anyMatch(Objects::isNull)) {
            throw new BizException(errorCode);
        }
    }

    /** 真值断言（FR-017）；失败抛 {@link BizException}。 */
    public static void isTrue(boolean condition, ErrorCode errorCode) {
        if (!condition) {
            throw new BizException(errorCode);
        }
    }

    /** 假值断言（FR-017）；失败抛 {@link BizException}。 */
    public static void isFalse(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    /** 状态断言（DP-007 定稿）：语义与 {@link #isTrue} 行为一致，用于状态机/不变量场景的表达性标注。 */
    public static void state(boolean condition, ErrorCode errorCode) {
        if (!condition) {
            throw new BizException(errorCode);
        }
    }
}
