package com.acme.common.serialization.internal;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Long←String 反序列化器（FR-024）：同时接受 JSON 字符串与 JSON 数值两种输入（架构设计 3.5.2）；
 * 非法值按 JacksonException 语义抛出，不吞异常。
 */
public final class LongToStringDeserializer extends ValueDeserializer<Long> {

    public static final LongToStringDeserializer INSTANCE = new LongToStringDeserializer();

    private LongToStringDeserializer() {
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            String text = p.getString();
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException e) {
                throw DatabindException.from(p, "无法解析为 Long：" + text, e);
            }
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        throw DatabindException.from(p, "Long 反序列化仅接受字符串或整数：" + token);
    }
}
