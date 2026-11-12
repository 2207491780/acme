package com.acme.common.validation;

import com.acme.common.validation.internal.InEnumValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InEnum 约束契约级语义测试（AC-005、架构设计 6.2；DP-013 定稿）：
 * 合法枚举值通过、非法值失败、null 视为通过（存在性交 @NotNull）。
 */
class InEnumValidatorTest {

    enum Color { RED, GREEN }

    static class Sample {
        @InEnum(enumClass = Color.class)
        Object value;
    }

    private static InEnumValidator newValidator() throws Exception {
        InEnum annotation = Sample.class.getDeclaredField("value").getAnnotation(InEnum.class);
        InEnumValidator validator = new InEnumValidator();
        validator.initialize(annotation);
        return validator;
    }

    @Test
    void legalEnumValuePasses() throws Exception {
        assertTrue(newValidator().isValid(Color.RED, null));
        assertTrue(newValidator().isValid(Color.GREEN, null));
    }

    @Test
    void illegalValueFails() throws Exception {
        assertFalse(newValidator().isValid("RED", null));
        assertFalse(newValidator().isValid(Color.class, null));
    }

    /** null 口径（DP-013 定稿）：null 视为通过，存在性校验交 @NotNull。 */
    @Test
    void nullValuePasses() throws Exception {
        assertTrue(newValidator().isValid(null, null));
    }
}
