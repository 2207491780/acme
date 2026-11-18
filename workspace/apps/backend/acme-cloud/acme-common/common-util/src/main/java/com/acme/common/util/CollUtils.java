package com.acme.common.util;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 集合工具（FR-002）。null 处理口径全类一致且显式。
 *
 * @implNote 暂缓 DP-001：完整方法集与 null 处理口径细则待定稿。
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class CollUtils {

    private CollUtils() {
    }

    /**
     * 判空（FR-002）：null 视为空。
     *
     * @implNote 暂缓 DP-001：口径细则随完整方法集定稿。
     */
    public static boolean isEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    /**
     * null 转空列表（FR-002）。
     *
     * @implNote 暂缓 DP-001：口径细则随完整方法集定稿。
     */
    public static <T> List<T> emptyIfNull(List<T> value) {
        return value == null ? List.of() : value;
    }

    /**
     * 不可变视图，null 转不可变空列表（FR-002）。
     *
     * @implNote 暂缓 DP-001：口径细则随完整方法集定稿。
     */
    public static <T> List<T> unmodifiableOrEmpty(List<T> value) {
        return value == null ? List.of() : Collections.unmodifiableList(value);
    }
}
