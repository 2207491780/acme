package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Instant 序列化器（FR-024）：ISO-8601 带 Z（UTC）（Modules.md 2.5.5、Q-009）。
 */
public final class InstantSerializer extends ValueSerializer<Instant> {

    public static final InstantSerializer INSTANCE = new InstantSerializer();

    private InstantSerializer() {
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(DateTimeFormatter.ISO_INSTANT.format(value));
    }
}
