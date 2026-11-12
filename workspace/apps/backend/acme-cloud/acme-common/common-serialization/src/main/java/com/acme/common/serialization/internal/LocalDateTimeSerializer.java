package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.LocalDateTime;

/**
 * LocalDateTime 序列化器（FR-024）：无偏移 {@code yyyy-MM-dd'T'HH:mm:ss}（Modules.md 2.5.5、Q-009）。
 */
public final class LocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {

    public static final LocalDateTimeSerializer INSTANCE = new LocalDateTimeSerializer();

    private LocalDateTimeSerializer() {
    }

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(TimeFormats.LOCAL_DATE_TIME.format(value));
    }
}
