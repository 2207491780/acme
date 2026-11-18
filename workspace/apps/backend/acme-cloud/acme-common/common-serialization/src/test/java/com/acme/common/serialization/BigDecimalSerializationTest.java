package com.acme.common.serialization;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BigDecimal 规整行为测试（AC-007/AC-018；DQ-006 去尾零 + plain 输出）。逐行为独立断言。
 */
class BigDecimalSerializationTest {

    private static JsonMapper mapper() {
        return JsonMapper.builder().addModule(new BigDecimalModule()).build();
    }

    /** ① 去尾零：100.00→100（DQ-006）。 */
    @Test
    void trailingZerosStripped() {
        assertEquals("100", mapper().writeValueAsString(new BigDecimal("100.00")));
    }

    /** ② 去尾零：1.50→1.5（DQ-006）。 */
    @Test
    void fractionTrailingZerosStripped() {
        assertEquals("1.5", mapper().writeValueAsString(new BigDecimal("1.50")));
    }

    /** ③ 零值规整为 0。 */
    @Test
    void zeroNormalized() {
        assertEquals("0", mapper().writeValueAsString(new BigDecimal("0.00")));
    }

    /** ④ plain 输出：禁科学计数法（1E+2 形态不得出现，DQ-006）。 */
    @Test
    void plainOutputWithoutScientificNotation() {
        assertEquals("100", mapper().writeValueAsString(new BigDecimal("1E+2")));
        assertEquals("10000", mapper().writeValueAsString(new BigDecimal("10000.00")));
        assertEquals("0.00001", mapper().writeValueAsString(new BigDecimal("0.00001")));
        assertEquals("-1.5", mapper().writeValueAsString(new BigDecimal("-1.50")));
    }

    /** ⑤ JsonModules.standard() 汇总下构造器注册体生效（AC-018、DQ-008）。 */
    @Test
    void standardModulesApplyBigDecimal() {
        JsonMapper mapper = JsonMapper.builder().addModules(JsonModules.standard()).build();
        assertEquals("100", mapper.writeValueAsString(new BigDecimal("100.00")));
    }

    /** ⑥ null 字段按 JSON null 输出（根级 null 由 mapper 原生处理，与 LongToStringSerializer 同口径）。 */
    @Test
    void nullSerializedAsNull() {
        assertEquals("{\"amount\":null}", mapper().writeValueAsString(new NullAmountBean()));
        assertEquals("null", mapper().writeValueAsString(null));
    }

    static class NullAmountBean {
        public BigDecimal amount;
    }
}
