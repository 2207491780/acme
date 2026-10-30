package com.acme.common.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 通用枚举契约级语义测试（AC-005、架构设计 6.2）：fromCode 已知编码转换。
 */
class YesNoEnabledTest {

    @Test
    void yesNoFromCode() {
        assertEquals(YesNo.YES, YesNo.fromCode(1));
        assertEquals(YesNo.NO, YesNo.fromCode(0));
        assertEquals(1, YesNo.YES.code());
        assertEquals(0, YesNo.NO.code());
    }

    @Test
    void enabledFromCode() {
        assertEquals(Enabled.ENABLED, Enabled.fromCode(1));
        assertEquals(Enabled.DISABLED, Enabled.fromCode(0));
    }
}
