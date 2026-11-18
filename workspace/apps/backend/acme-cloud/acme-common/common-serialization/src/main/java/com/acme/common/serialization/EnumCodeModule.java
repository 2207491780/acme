package com.acme.common.serialization;

import com.acme.common.serialization.internal.EnumCodeDeserializer;
import com.acme.common.serialization.internal.EnumCodeSerializer;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 枚举 code 序列化 Module（FR-024）：枚举按 code 序列化，识别口径与输出形态见 DQ-005。
 * 识别口径（@JsonValue / 方法约定）不得引用 core 类型（BR-002、AC-007）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class EnumCodeModule extends SimpleModule {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public EnumCodeModule() {
        super("EnumCodeModule");
        // Enum 级兜底注册（DQ-005；Jackson 3 SimpleModule 支持，实现期核实 SimpleSerializers._hasEnumSerializer）
        addSerializer(Enum.class, EnumCodeSerializer.INSTANCE);
        addDeserializer(Enum.class, (ValueDeserializer) (ValueDeserializer<?>) EnumCodeDeserializer.INSTANCE);
    }
}
