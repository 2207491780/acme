package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 枚举 code 序列化器（FR-024、DQ-005）：识别口径 {@code @JsonValue} &gt; {@code code()} 方法约定 &gt; {@code name()} 兜底；
 * code 输出随返回类型原生输出（数值→JSON 数值、字符串→JSON 字符串）。
 * 访问器经 {@link EnumCodeMeta} 一次解析缓存，热路径零反射扫描。
 * 不引用 common-core 类型（EnumCodeModule Javadoc BR-002 口径）；仅经
 * {@link com.acme.common.serialization.EnumCodeModule} 暴露（Modules.md 2.5.2）。
 */
public final class EnumCodeSerializer extends ValueSerializer<Object> {

    public static final EnumCodeSerializer INSTANCE = new EnumCodeSerializer();

    private EnumCodeSerializer() {
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        writeCode(gen, EnumCodeMeta.of(value.getClass()).encode(value));
    }

    /** code 原生输出（DQ-005）：数值→JSON 数值、字符串→JSON 字符串。 */
    static void writeCode(JsonGenerator gen, Object code) {
        if (code == null) {
            gen.writeNull();
        } else if (code instanceof Integer || code instanceof Long || code instanceof Short || code instanceof Byte) {
            gen.writeNumber(((Number) code).longValue());
        } else if (code instanceof BigInteger big) {
            gen.writeNumber(big);
        } else if (code instanceof BigDecimal big) {
            gen.writeNumber(big);
        } else if (code instanceof Double || code instanceof Float) {
            gen.writeNumber(((Number) code).doubleValue());
        } else if (code instanceof String text) {
            gen.writeString(text);
        } else if (code instanceof Boolean flag) {
            gen.writeBoolean(flag);
        } else if (code instanceof Character ch) {
            gen.writeString(ch.toString());
        } else {
            gen.writeString(String.valueOf(code));
        }
    }
}
