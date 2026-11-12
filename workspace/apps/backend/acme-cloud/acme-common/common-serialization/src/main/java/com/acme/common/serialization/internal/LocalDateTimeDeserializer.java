package com.acme.common.serialization.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * LocalDateTime 反序列化器（FR-024）：接受无偏移文本往返；带偏移量文本按 GMT+8 转墙上时间（Q-009 互转口径）。
 * 非法值按 JacksonException 语义抛出（FR-024 异常分支）。
 */
public final class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

    public static final LocalDateTimeDeserializer INSTANCE = new LocalDateTimeDeserializer();

    private LocalDateTimeDeserializer() {
    }

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        try {
            return TimeFormats.parseLocalDateTime(text);
        } catch (DateTimeParseException e) {
            throw DatabindException.from(p, "无法解析为 LocalDateTime：" + text, e);
        }
    }
}
