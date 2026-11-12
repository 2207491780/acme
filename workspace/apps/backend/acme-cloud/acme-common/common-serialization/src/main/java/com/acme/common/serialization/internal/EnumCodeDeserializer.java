package com.acme.common.serialization.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

/**
 * 枚举 code 反序列化器（FR-024、DQ-005 双向补全）：识别口径 {@code @JsonValue} 匹配 &gt;
 * {@code fromCode} 静态方法约定（与 YesNo/Enabled 同款）&gt; {@code code()} 匹配 &gt; {@code name()} 匹配；
 * 未知值按 Jackson 异常语义抛出（FR-024 异常分支），不吞异常。
 * 匹配表经 {@link EnumCodeMeta} 一次解析缓存，热路径零反射扫描。
 * 不引用 common-core 类型（EnumCodeModule Javadoc BR-002 口径）；仅经
 * {@link com.acme.common.serialization.EnumCodeModule} 暴露（Modules.md 2.5.2）。
 */
public final class EnumCodeDeserializer extends ValueDeserializer<Object> {

    public static final EnumCodeDeserializer INSTANCE = new EnumCodeDeserializer();

    /** 绑定的枚举类型；空表示运行时取上下文目标类型（DQ-005）。 */
    private final Class<?> enumType;

    private EnumCodeDeserializer() {
        this.enumType = null;
    }

    private EnumCodeDeserializer(Class<?> enumType) {
        this.enumType = enumType;
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        Class<?> resolved = resolveType(ctxt, property);
        if (resolved != null && resolved != enumType) {
            return new EnumCodeDeserializer(resolved);
        }
        return this;
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token != JsonToken.VALUE_STRING && token != JsonToken.VALUE_NUMBER_INT
                && token != JsonToken.VALUE_NUMBER_FLOAT) {
            throw DatabindException.from(p, "枚举反序列化仅接受字符串或数值：" + token);
        }
        Object input = token == JsonToken.VALUE_STRING ? p.getString() : p.getNumberValue();
        Class<?> type = enumType != null ? enumType : resolveType(ctxt, null);
        if (type == null || !type.isEnum()) {
            throw DatabindException.from(p, "枚举反序列化缺少目标枚举类型（DQ-005）");
        }
        return convert(p, type, input);
    }

    private static Object convert(JsonParser p, Class<?> type, Object input) {
        EnumCodeMeta meta = EnumCodeMeta.of(type);
        Object matched = meta.matchByJsonValue(input);
        if (matched != null) {
            return matched;
        }
        Object byFromCode;
        try {
            byFromCode = meta.invokeFromCode(input);
        } catch (IllegalArgumentException e) {
            throw DatabindException.from(p, e.getMessage(), e);
        }
        if (byFromCode != null) {
            return byFromCode;
        }
        matched = meta.matchByCode(input);
        if (matched != null) {
            return matched;
        }
        matched = meta.matchByName(input);
        if (matched != null) {
            return matched;
        }
        throw DatabindException.from(p, "未知枚举编码（FR-024）：" + input);
    }

    private static Class<?> resolveType(DeserializationContext ctxt, BeanProperty property) {
        if (property != null) {
            JavaType propertyType = property.getType();
            if (propertyType != null && propertyType.isEnumType()) {
                return propertyType.getRawClass();
            }
        }
        JavaType contextual = ctxt.getContextualType();
        if (contextual != null && contextual.isEnumType()) {
            return contextual.getRawClass();
        }
        return null;
    }
}
