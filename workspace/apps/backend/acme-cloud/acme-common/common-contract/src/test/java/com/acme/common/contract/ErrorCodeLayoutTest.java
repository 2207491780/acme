package com.acme.common.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ErrorCodeLayout 契约级语义测试（AC-005、架构设计 6.2）：code()/parse() 往返一致（Q-010 结论）。
 */
class ErrorCodeLayoutTest {

    @Test
    void codeAndParseRoundTrip() {
        ErrorCodeLayout layout = new ErrorCodeLayout("order", 10, 1);
        assertEquals("order-10-0001", layout.code());
        assertEquals(layout, ErrorCodeLayout.parse("order-10-0001"));
    }

    @Test
    void codePadsSegmentsToWidth() {
        assertEquals("pay-01-0009", new ErrorCodeLayout("pay", 1, 9).code());
    }

    @Test
    void parseRejectsMalformedCode() {
        assertThrows(IllegalArgumentException.class, () -> ErrorCodeLayout.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ErrorCodeLayout.parse("order-10"));
        assertThrows(IllegalArgumentException.class, () -> ErrorCodeLayout.parse("order-abc-0001"));
    }
}
