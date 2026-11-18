package com.acme.common.serialization;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 时间格式与时区行为测试（AC-004；架构设计 6.1、Q-009 结论）。逐行为独立断言。
 */
class TimeFormatSerializationTest {

    private static JsonMapper mapper() {
        return JsonMapper.builder().addModule(new TimeFormatModule()).build();
    }

    /** ① Instant 序列化输出带 Z（UTC）。 */
    @Test
    void instantSerializedWithZ() {
        String json = mapper().writeValueAsString(new InstantBean(Instant.parse("2026-10-19T10:15:30Z")));
        assertEquals("{\"value\":\"2026-10-19T10:15:30Z\"}", json);
    }

    /** ② LocalDateTime 输出无偏移 yyyy-MM-dd'T'HH:mm:ss；LocalDate 输出 ISO-8601 yyyy-MM-dd（2.5.5）。 */
    @Test
    void localDateTimeAndLocalDateSerializedWithoutOffset() {
        JsonMapper mapper = mapper();
        assertEquals("{\"value\":\"2026-10-19T18:15:30\"}",
                mapper.writeValueAsString(new LocalDateTimeBean(LocalDateTime.of(2026, 10, 19, 18, 15, 30))));
        assertEquals("{\"value\":\"2026-10-19\"}",
                mapper.writeValueAsString(new LocalDateBean(LocalDate.of(2026, 10, 19))));
    }

    /** ③ 反序列化接受同一 ISO-8601 口径，往返对称。 */
    @Test
    void roundTripIsSymmetric() {
        JsonMapper mapper = mapper();
        Instant instant = Instant.parse("2026-10-19T10:15:30Z");
        LocalDateTime localDateTime = LocalDateTime.of(2026, 10, 19, 18, 15, 30);
        LocalDate localDate = LocalDate.of(2026, 10, 19);
        assertEquals(instant, mapper.readValue(mapper.writeValueAsString(new InstantBean(instant)), InstantBean.class).value);
        assertEquals(localDateTime,
                mapper.readValue(mapper.writeValueAsString(new LocalDateTimeBean(localDateTime)), LocalDateTimeBean.class).value);
        assertEquals(localDate, mapper.readValue(mapper.writeValueAsString(new LocalDateBean(localDate)), LocalDateBean.class).value);
    }

    /** ④ Instant ↔ LocalDateTime 互转按统一时区 GMT+8 解释（Q-009 结论）。 */
    @Test
    void instantAndLocalDateTimeInteropUsesGmt8() {
        JsonMapper mapper = mapper();
        // 无偏移文本解到 Instant 字段：按 GMT+8 解释为瞬时量
        Instant instant = mapper.readValue("{\"value\":\"2026-10-19T18:15:30\"}", InstantBean.class).value;
        assertEquals(Instant.parse("2026-10-19T10:15:30Z"), instant);
        // 带 Z 文本解到 LocalDateTime 字段：按 GMT+8 取墙上时间
        LocalDateTime localDateTime = mapper.readValue("{\"value\":\"2026-10-19T10:15:30Z\"}", LocalDateTimeBean.class).value;
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 15, 30), localDateTime);
    }

    static class InstantBean {
        public Instant value;

        InstantBean() {
        }

        InstantBean(Instant value) {
            this.value = value;
        }
    }

    static class LocalDateTimeBean {
        public LocalDateTime value;

        LocalDateTimeBean() {
        }

        LocalDateTimeBean(LocalDateTime value) {
            this.value = value;
        }
    }

    static class LocalDateBean {
        public LocalDate value;

        LocalDateBean() {
        }

        LocalDateBean(LocalDate value) {
            this.value = value;
        }
    }
}
