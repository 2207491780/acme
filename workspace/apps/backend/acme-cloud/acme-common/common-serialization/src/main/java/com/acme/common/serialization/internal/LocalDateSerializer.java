package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.LocalDate;

/**
 * LocalDate 序列化器（FR-024）：ISO-8601 {@code yyyy-MM-dd}（Modules.md 2.5.5）。
 */
public final class LocalDateSerializer extends ValueSerializer<LocalDate> {

    public static final LocalDateSerializer INSTANCE = new LocalDateSerializer();

    private LocalDateSerializer() {
    }

    @Override
    public void serialize(LocalDate value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(TimeFormats.LOCAL_DATE.format(value));
    }
}
