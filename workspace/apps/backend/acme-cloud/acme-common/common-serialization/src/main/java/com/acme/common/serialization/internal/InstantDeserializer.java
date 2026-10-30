package com.acme.common.serialization.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Instant 反序列化器（FR-024）：接受带 Z（UTC）文本往返；无偏移文本按 GMT+8 解释（Q-009 互转口径）。
 * 非法值按 JacksonException 语义抛出（FR-024 异常分支）。
 */
public final class InstantDeserializer extends ValueDeserializer<Instant> {

    public static final InstantDeserializer INSTANCE = new InstantDeserializer();

    private InstantDeserializer() {
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        try {
            return TimeFormats.parseInstant(text);
        } catch (DateTimeParseException e) {
            throw DatabindException.from(p, "无法解析为 Instant：" + text, e);
        }
    }
}
