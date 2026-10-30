package com.acme.common.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MessageKeys 契约级语义测试（AC-005、架构设计 6.2）：of(...) 键拼接约定可调用。
 */
class MessageKeysTest {

    @Test
    void joinsSegmentsWithDot() {
        assertEquals("validation.inEnum", MessageKeys.of("validation", "inEnum"));
        assertEquals("a.b.c", MessageKeys.of("a", "b", "c"));
    }

    @Test
    void emptySegmentsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, MessageKeys::of);
    }
}
