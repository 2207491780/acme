package com.acme.common.serialization;

import com.acme.common.serialization.internal.InstantDeserializer;
import com.acme.common.serialization.internal.InstantSerializer;
import com.acme.common.serialization.internal.LocalDateDeserializer;
import com.acme.common.serialization.internal.LocalDateSerializer;
import com.acme.common.serialization.internal.LocalDateTimeDeserializer;
import com.acme.common.serialization.internal.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 时间格式与时区标准 Module（FR-024；Modules.md 2.5.5 日期一律 ISO-8601）。
 * 可验证实现（AC-004；Q-009 结论）：Instant 输出带 Z（UTC）；LocalDateTime 输出无偏移 {@code yyyy-MM-dd'T'HH:mm:ss}；
 * Instant ↔ LocalDateTime 互转按统一时区 GMT+8 解释；展示格式由前端负责（2.5.5）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TimeFormatModule extends SimpleModule {

    public TimeFormatModule() {
        super("TimeFormatModule");
        addSerializer(Instant.class, InstantSerializer.INSTANCE);
        addSerializer(LocalDateTime.class, LocalDateTimeSerializer.INSTANCE);
        addSerializer(LocalDate.class, LocalDateSerializer.INSTANCE);
        addDeserializer(Instant.class, InstantDeserializer.INSTANCE);
        addDeserializer(LocalDateTime.class, LocalDateTimeDeserializer.INSTANCE);
        addDeserializer(LocalDate.class, LocalDateDeserializer.INSTANCE);
    }
}
