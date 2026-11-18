package com.acme.common.serialization;

import com.acme.common.serialization.internal.LongToStringDeserializer;
import com.acme.common.serialization.internal.LongToStringSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Long→String 标准 Module（FR-024；Modules.md 2.5.1）。全局作用于 Long/long（含计数类字段，架构设计 11）。
 * 可验证实现（AC-004）：序列化输出 JSON 字符串（MAX_VALUE 不丢精度）；反序列化接受字符串与数值两种输入。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class LongToStringModule extends SimpleModule {

    public LongToStringModule() {
        super("LongToStringModule");
        addSerializer(Long.class, LongToStringSerializer.INSTANCE);
        addSerializer(long.class, LongToStringSerializer.INSTANCE);
        addDeserializer(Long.class, LongToStringDeserializer.INSTANCE);
        addDeserializer(long.class, LongToStringDeserializer.INSTANCE);
    }
}
