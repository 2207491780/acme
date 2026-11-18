package com.acme.common.serialization.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * LocalDate 反序列化器（FR-024）：ISO-8601 {@code yyyy-MM-dd} 往返（Modules.md 2.5.5）。
 * 非法值按 JacksonException 语义抛出（FR-024 异常分支）。
 */
public final class LocalDateDeserializer extends ValueDeserializer<LocalDate> {

    public static final LocalDateDeserializer INSTANCE = new LocalDateDeserializer();

    private LocalDateDeserializer() {
    }

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        try {
            return TimeFormats.parseLocalDate(text);
        } catch (DateTimeParseException e) {
            throw DatabindException.from(p, "无法解析为 LocalDate：" + text, e);
        }
    }
}
