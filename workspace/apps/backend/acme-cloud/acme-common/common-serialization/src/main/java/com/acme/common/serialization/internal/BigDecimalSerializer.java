package com.acme.common.serialization.internal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;

/**
 * BigDecimal 规整序列化器（FR-026、DQ-006）：stripTrailingZeros 去尾零 + plain 数值输出（禁科学计数法，
 * 100.00→100、1.50→1.5）；「统一小数位」待金额口径定稿后经新增带参构造器补（DQ-006）。
 * 仅经 {@link com.acme.common.serialization.BigDecimalModule} 暴露（Modules.md 2.5.2）。
 */
public final class BigDecimalSerializer extends ValueSerializer<BigDecimal> {

    public static final BigDecimalSerializer INSTANCE = new BigDecimalSerializer();

    private BigDecimalSerializer() {
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeNumber(value.stripTrailingZeros().toPlainString());
    }
}
