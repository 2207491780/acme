package com.acme.common.serialization.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 时间格式口径（FR-024；Modules.md 2.5.5、Q-009 结论）：Instant 带 Z（UTC）、LocalDateTime 无偏移、
 * 互转按统一时区 GMT+8 解释。实现细节（Modules.md 2.3：模块外不得引用）。
 */
public final class TimeFormats {

    /** 统一互转时区（Q-009 结论：GMT+8）。 */
    public static final ZoneId ZONE_GMT8 = ZoneId.of("GMT+8");

    /** LocalDateTime 无偏移格式（Q-009）。 */
    public static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** LocalDate 格式（Modules.md 2.5.5 ISO-8601）。 */
    public static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 偏移量后缀（预编译，热路径零重复编译）。 */
    private static final Pattern OFFSET_SUFFIX = Pattern.compile("[+-]\\d{2}:\\d{2}$");

    private TimeFormats() {
    }

    /**
     * 文本 → Instant：含偏移量（Z 或 ±HH:MM）按瞬时量解析；无偏移按 GMT+8 解释（Q-009 互转口径）。
     */
    public static Instant parseInstant(String text) {
        if (hasOffset(text)) {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZONE_GMT8).toInstant();
    }

    /**
     * 文本 → LocalDateTime：无偏移直接解析；含偏移量先转 Instant 再按 GMT+8 取墙上时间（Q-009 互转口径）。
     */
    public static LocalDateTime parseLocalDateTime(String text) {
        if (hasOffset(text)) {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(), ZONE_GMT8);
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 文本 → LocalDate（Modules.md 2.5.5）。
     */
    public static LocalDate parseLocalDate(String text) {
        return LocalDate.parse(text, DateTimeFormatter.ISO_DATE);
    }

    static boolean hasOffset(String text) {
        return text.endsWith("Z") || text.endsWith("z") || OFFSET_SUFFIX.matcher(text).find();
    }
}
