package com.acme.common.serialization;

import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Json 门面契约测试（2.5.3、BR-011）：编解码失败统一转 BizException 且不抛受检异常。
 */
class JsonTest {

    private final Json json = new Json(JsonMapper.builder().build());

    record Point(int x, int y) {
    }

    static class Node {
        @SuppressWarnings("unused")
        public Node self;
    }

    @Test
    void toJsonAndBack() {
        assertEquals("{\"x\":1,\"y\":2}", json.toJson(new Point(1, 2)));
        assertEquals(new Point(1, 2), json.fromJson("{\"x\":1,\"y\":2}", Point.class));
    }

    @Test
    void fromJsonFailureBecomesBizException() {
        BizException e = assertThrows(BizException.class, () -> json.fromJson("{bad", Point.class));
        assertEquals("SYS-00-0007", e.errorCode().code());
        assertTrue(e.getCause() instanceof tools.jackson.core.JacksonException);
    }

    @Test
    void toJsonFailureBecomesBizException() {
        Node cycle = new Node();
        cycle.self = cycle;
        BizException e = assertThrows(BizException.class, () -> json.toJson(cycle));
        assertEquals("SYS-00-0007", e.errorCode().code());
        assertTrue(e.getCause() instanceof tools.jackson.core.JacksonException);
    }

    @Test
    void mapperRequired() {
        assertThrows(IllegalArgumentException.class, () -> new Json(null));
    }
}
