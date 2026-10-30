package com.acme.common.util;

/**
 * 字符串工具（FR-002）。null 处理口径全类一致且显式，不得同一能力混用「返回空」与「抛异常」。
 *
 * <p>失败形态口径（DP-001 定稿，全类统一）：判空类方法（isEmpty/isBlank）对 null 容忍返回 true；
 * 转换类方法参数为 null → NullPointerException、参数非法 → IllegalArgumentException。</p>
 *
 * <p>方法集定稿为现状三项；后续以兼容方式按需新增（Backend.md 7.2.1）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class StrUtils {

    private StrUtils() {
    }

    /** 判空（FR-002）：null 视为空（DP-001 定稿）。 */
    public static boolean isEmpty(CharSequence value) {
        return value == null || value.isEmpty();
    }

    /** 判空白（FR-002）：null/空白均 true（DP-001 定稿）。 */
    public static boolean isBlank(CharSequence value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 截取（FR-002）；null → NPE、maxLength 为负 → IllegalArgumentException（DP-001 定稿）。 */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            throw new NullPointerException("value 不能为空");
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength 不得为负");
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
