package com.acme.common.serialization;

import com.acme.common.serialization.internal.BigDecimalSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

/**
 * BigDecimal 规整 Module（FR-026）：去尾零 + plain 输出（DQ-006）；「统一小数位」待金额口径定稿后经新增带参构造器补（DQ-006）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class BigDecimalModule extends SimpleModule {

    public BigDecimalModule() {
        super("BigDecimalModule");
        addSerializer(BigDecimal.class, BigDecimalSerializer.INSTANCE);
    }
}
