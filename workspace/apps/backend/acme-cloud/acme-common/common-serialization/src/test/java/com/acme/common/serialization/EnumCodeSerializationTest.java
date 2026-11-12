package com.acme.common.serialization;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 枚举 code 双向行为测试（AC-007/AC-018；DQ-005 识别顺序、输出形态、异常分支）。逐行为独立断言。
 */
class EnumCodeSerializationTest {

    private static JsonMapper mapper() {
        return JsonMapper.builder().addModule(new EnumCodeModule()).build();
    }

    /** ① code()/fromCode 约定枚举（与 YesNo/Enabled 同款）按 code 输出 JSON 数值（DQ-005）。 */
    @Test
    void codeEnumSerializedAsNumber() {
        assertEquals("1", mapper().writeValueAsString(CodeEnum.ALPHA));
    }

    /** ② code 枚举反序列化接受数值与字符串两种输入并可往返（DQ-005 双向补全）。 */
    @Test
    void codeEnumRoundTrips() {
        JsonMapper mapper = mapper();
        assertEquals(CodeEnum.ALPHA, mapper.readValue("1", CodeEnum.class));
        assertEquals(CodeEnum.ALPHA, mapper.readValue("\"1\"", CodeEnum.class));
        assertEquals(CodeEnum.BETA, mapper.readValue("2", CodeEnum.class));
    }

    /** ③ 字符串 code 随返回类型原生输出 JSON 字符串（DQ-005）。 */
    @Test
    void stringCodeSerializedAsString() {
        JsonMapper mapper = mapper();
        assertEquals("\"x\"", mapper.writeValueAsString(TextCodeEnum.X));
        assertEquals(TextCodeEnum.X, mapper.readValue("\"x\"", TextCodeEnum.class));
    }

    /** ④ @JsonValue 识别优先于 code()（DQ-005 识别顺序首位）。 */
    @Test
    void jsonValueWinsOverCode() {
        JsonMapper mapper = mapper();
        assertEquals("\"json-a\"", mapper.writeValueAsString(JsonValueEnum.A));
        assertEquals(JsonValueEnum.A, mapper.readValue("\"json-a\"", JsonValueEnum.class));
    }

    /** ⑤ 无 code 枚举以 name() 兜底（DQ-005）。 */
    @Test
    void plainEnumFallsBackToName() {
        JsonMapper mapper = mapper();
        assertEquals("\"RED\"", mapper.writeValueAsString(PlainEnum.RED));
        assertEquals(PlainEnum.RED, mapper.readValue("\"RED\"", PlainEnum.class));
    }

    /** ⑥ name() 匹配对 code 枚举同样兜底（DQ-005：name() 兜底）。 */
    @Test
    void nameFallbackMatchesCodeEnum() {
        assertEquals(CodeEnum.BETA, mapper().readValue("\"BETA\"", CodeEnum.class));
    }

    /** ⑦ 无 fromCode 但有 code() 的枚举按 code() 返回值匹配反序列化（DQ-005：code() &gt; name()）。 */
    @Test
    void codeMethodMatchedWithoutFromCode() {
        JsonMapper mapper = mapper();
        assertEquals(CodeOnlyEnum.NINE, mapper.readValue("9", CodeOnlyEnum.class));
    }

    /** ⑧ 未知值按 Jackson 异常语义抛出，不吞异常（FR-024 异常分支）。 */
    @Test
    void unknownValueFailsWithJacksonException() {
        JsonMapper mapper = mapper();
        assertThrows(JacksonException.class, () -> mapper.readValue("99", CodeEnum.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("\"NOPE\"", PlainEnum.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("true", CodeEnum.class));
    }

    /** ⑨ JsonModules.standard() 汇总下构造器注册体生效（AC-018、DQ-008）。 */
    @Test
    void standardModulesApplyEnumCode() {
        JsonMapper mapper = JsonMapper.builder().addModules(JsonModules.standard()).build();
        assertEquals("1", mapper.writeValueAsString(CodeEnum.ALPHA));
        assertEquals(CodeEnum.ALPHA, mapper.readValue("1", CodeEnum.class));
    }

    /** ⑩ YesNo 既有约定（int code() + static fromCode(int)）可直接往返（DQ-005 同款口径）。 */
    @Test
    void yesNoConventionRoundTrips() {
        JsonMapper mapper = mapper();
        assertEquals("1", mapper.writeValueAsString(com.acme.common.core.YesNo.YES));
        assertEquals(com.acme.common.core.YesNo.YES, mapper.readValue("1", com.acme.common.core.YesNo.class));
    }

    /** ⑪ 非常规 code 类型随返回类型原生输出（DQ-005 输出形态边界）。 */
    @Test
    void exoticCodeTypesSerializedNatively() {
        JsonMapper mapper = mapper();
        assertEquals("1234567890123456789012", mapper.writeValueAsString(BigIntCodeEnum.A));
        assertEquals("1.5", mapper.writeValueAsString(BigDecCodeEnum.A));
        assertEquals("1.5", mapper.writeValueAsString(DoubleCodeEnum.A));
        assertEquals("true", mapper.writeValueAsString(BoolCodeEnum.A));
        assertEquals("\"q\"", mapper.writeValueAsString(CharCodeEnum.A));
        assertEquals("\"odd\"", mapper.writeValueAsString(OddCodeEnum.A));
    }

    /** ⑫ code() 返回 null 输出 JSON null。 */
    @Test
    void nullCodeSerializedAsNull() {
        assertEquals("null", mapper().writeValueAsString(NullCodeEnum.A));
    }

    /** ⑬ @JsonValue 字段形式同样被识别（DQ-005）。 */
    @Test
    void jsonValueOnFieldIsRecognized() {
        JsonMapper mapper = mapper();
        assertEquals("\"field-v\"", mapper.writeValueAsString(FieldJsonValueEnum.A));
        assertEquals(FieldJsonValueEnum.A, mapper.readValue("\"field-v\"", FieldJsonValueEnum.class));
    }

    /** ⑭ fromCode 长整型参数约定（fromCode 单参变体）。 */
    @Test
    void fromCodeWithLongParameter() {
        assertEquals(LongCodeEnum.A, mapper().readValue("7", LongCodeEnum.class));
    }

    enum CodeEnum {
        ALPHA(1),
        BETA(2);

        private final int code;

        CodeEnum(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static CodeEnum fromCode(int code) {
            for (CodeEnum value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知编码：" + code);
        }
    }

    enum TextCodeEnum {
        X("x"),
        Y("y");

        private final String code;

        TextCodeEnum(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        static TextCodeEnum fromCode(String code) {
            for (TextCodeEnum value : values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知编码：" + code);
        }
    }

    enum JsonValueEnum {
        A("json-a", 10),
        B("json-b", 20);

        private final String jsonValue;

        private final int code;

        JsonValueEnum(String jsonValue, int code) {
            this.jsonValue = jsonValue;
            this.code = code;
        }

        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        public int code() {
            return code;
        }

        static JsonValueEnum fromCode(int code) {
            for (JsonValueEnum value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知编码：" + code);
        }
    }

    enum PlainEnum {
        RED,
        GREEN
    }

    enum CodeOnlyEnum {
        NINE(9);

        private final int code;

        CodeOnlyEnum(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    enum BigIntCodeEnum {
        A;

        public java.math.BigInteger code() {
            return new java.math.BigInteger("1234567890123456789012");
        }
    }

    enum BigDecCodeEnum {
        A;

        public java.math.BigDecimal code() {
            return new java.math.BigDecimal("1.5");
        }
    }

    enum DoubleCodeEnum {
        A;

        public double code() {
            return 1.5d;
        }
    }

    enum BoolCodeEnum {
        A;

        public boolean code() {
            return true;
        }
    }

    enum CharCodeEnum {
        A;

        public char code() {
            return 'q';
        }
    }

    enum OddCodeEnum {
        A;

        public Object code() {
            return new Object() {
                @Override
                public String toString() {
                    return "odd";
                }
            };
        }
    }

    enum NullCodeEnum {
        A;

        public String code() {
            return null;
        }
    }

    enum FieldJsonValueEnum {
        A;

        @JsonValue
        private final String jsonValue = "field-v";
    }

    enum LongCodeEnum {
        A(7L);

        private final long code;

        LongCodeEnum(long code) {
            this.code = code;
        }

        public long code() {
            return code;
        }

        static LongCodeEnum fromCode(long code) {
            for (LongCodeEnum value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知编码：" + code);
        }
    }
}
