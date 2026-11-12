package com.acme.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 日期时间工具（FR-001）。封装 JDK 时间 API 常用口径；参数非法显式失败，不返回可能被误用的默认值。
 * pattern 编译结果按文本缓存复用，热路径零重复编译。
 *
 * <p>失败形态口径（DP-001 定稿，全类统一）：参数为 null → NullPointerException；
 * 参数非法（如负长度、空 pattern）→ IllegalArgumentException；文本不可解析透传 JDK
 * {@code DateTimeParseException}（保留偏移信息便于定位）；同一能力不混用「返回空」与「抛异常」。</p>
 *
 * <p>方法集定稿为现状三项；后续以兼容方式按需新增（Backend.md 7.2.1）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class DateTimes {

    /** pattern → 编译器缓存（pattern 文本集合有限，进程内稳定）。 */
    private static final ConcurrentMap<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    private DateTimes() {
    }

    private static DateTimeFormatter formatter(String pattern) {
        return FORMATTERS.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }

    /** 按 pattern 格式化（FR-001）；失败形态见类级口径（DP-001 定稿）。 */
    public static String format(TemporalAccessor value, String pattern) {
        Objects.requireNonNull(value, "value 不能为空");
        Objects.requireNonNull(pattern, "pattern 不能为空");
        return formatter(pattern).format(value);
    }

    /** 按 pattern 解析为 LocalDateTime（FR-001）；文本不可解析透传 DateTimeParseException（DP-001 定稿）。 */
    public static LocalDateTime parseLocalDateTime(String text, String pattern) {
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(pattern, "pattern 不能为空");
        return LocalDateTime.parse(text, formatter(pattern));
    }

    /** 解析为 Instant（FR-001）；无偏移文本按 zone 解释；不可解析透传 DateTimeParseException（DP-001 定稿）。 */
    public static Instant parseInstant(String text, ZoneId zone) {
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(zone, "zone 不能为空");
        return LocalDateTime.parse(text).atZone(zone).toInstant();
    }
}
