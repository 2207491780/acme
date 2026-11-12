package com.acme.framework.jackson;

import com.acme.common.serialization.Desensitize;
import com.acme.common.serialization.DesensitizeStrategy;
import com.acme.framework.jackson.config.JacksonAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注入的 JsonMapper 序列化行为测试（FR-009、AC-007 全口径判定；BR-019、Modules.md 2.5.5）。
 */
class JsonMapperSerializationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                    JacksonAutoConfiguration.class));

    /** AC-007：Long→String（大值不丢精度）、时间 ISO-8601、枚举 code、脱敏掩码、BigDecimal 规整逐项断言。 */
    @Test
    void sampleObjectSerializedByUnifiedRules() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            String json = mapper.writeValueAsString(new SampleBean());
            assertThat(json).contains("\"bigId\":\"9223372036854775807\"");
            assertThat(json).contains("\"time\":\"2026-10-19T12:30:45\"");
            assertThat(json).contains("\"flag\":1");
            assertThat(json).contains("\"phone\":\"138****5678\"");
            assertThat(json).contains("\"amount\":100");
            assertThat(json).doesNotContain("1E+2").doesNotContain("1E2");
        });
    }

    /** BigDecimal 规整补充断言（DQ-006：1.50→1.5）。 */
    @Test
    void bigDecimalStripsTrailingZeros() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(new BigDecimal("1.50"))).isEqualTo("1.5");
            assertThat(mapper.writeValueAsString(new BigDecimal("100.00"))).isEqualTo("100");
        });
    }

    /** 枚举 code 双向（DQ-005）。 */
    @Test
    void enumCodeRoundTrips() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(SampleCodeEnum.BETA)).isEqualTo("2");
            assertThat(mapper.readValue("2", SampleCodeEnum.class)).isEqualTo(SampleCodeEnum.BETA);
        });
    }

    static class SampleBean {
        public BigDecimal amount = new BigDecimal("100.00");
        public Long bigId = Long.MAX_VALUE;
        public SampleCodeEnum flag = SampleCodeEnum.ALPHA;
        @Desensitize(strategy = DesensitizeStrategy.PHONE)
        public String phone = "13812345678";
        public LocalDateTime time = LocalDateTime.of(2026, 10, 19, 12, 30, 45);
    }

    enum SampleCodeEnum {
        ALPHA(1),
        BETA(2);

        private final int code;

        SampleCodeEnum(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static SampleCodeEnum fromCode(int code) {
            for (SampleCodeEnum value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知编码：" + code);
        }
    }
}
