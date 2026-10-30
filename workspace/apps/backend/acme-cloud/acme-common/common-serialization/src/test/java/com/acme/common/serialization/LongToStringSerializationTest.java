package com.acme.common.serialization;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Long→String 行为测试（AC-004；架构设计 6.1）。逐行为独立断言。
 */
class LongToStringSerializationTest {

    private static JsonMapper mapper() {
        return JsonMapper.builder().addModule(new LongToStringModule()).build();
    }

    /** ① Long 序列化输出为 JSON 字符串。 */
    @Test
    void longSerializedAsString() {
        String json = mapper().writeValueAsString(new LongBean(123L));
        assertEquals("{\"id\":\"123\"}", json);
    }

    /** ② Long.MAX_VALUE 序列化/反序列化往返不丢精度。 */
    @Test
    void maxValueRoundTripKeepsPrecision() {
        JsonMapper mapper = mapper();
        String json = mapper.writeValueAsString(new LongBean(Long.MAX_VALUE));
        LongBean bean = mapper.readValue(json, LongBean.class);
        assertEquals(Long.MAX_VALUE, bean.id);
    }

    /** ③ 反序列化接受 JSON 字符串与 JSON 数值两种输入。 */
    @Test
    void acceptsStringAndNumberInput() {
        JsonMapper mapper = mapper();
        assertEquals(123L, mapper.readValue("{\"id\":\"123\"}", LongBean.class).id);
        assertEquals(123L, mapper.readValue("{\"id\":123}", LongBean.class).id);
    }

    /** ④ 非法值按 Jackson 异常语义失败，不吞异常。 */
    @Test
    void invalidValueFailsWithJacksonException() {
        JsonMapper mapper = mapper();
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"id\":\"abc\"}", LongBean.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"id\":true}", LongBean.class));
    }

    static class LongBean {
        public Long id;

        LongBean() {
        }

        LongBean(Long id) {
            this.id = id;
        }
    }
}
