package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Long→String 序列化器（FR-024）：Long/long 值输出为 JSON 字符串，Long.MAX_VALUE 不丢精度（架构设计 3.5.2）。
 * 仅经 {@link com.acme.common.serialization.LongToStringModule} 暴露（Modules.md 2.5.2）。
 */
public final class LongToStringSerializer extends ValueSerializer<Long> {

    public static final LongToStringSerializer INSTANCE = new LongToStringSerializer();

    private LongToStringSerializer() {
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
        // null 按 Jackson 默认处理（架构设计 3.5.2）；防御性兜底不吞异常
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(Long.toString(value));
    }
}
