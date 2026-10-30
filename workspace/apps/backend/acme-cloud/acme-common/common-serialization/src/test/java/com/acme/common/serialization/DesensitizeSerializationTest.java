package com.acme.common.serialization;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

import com.acme.common.serialization.internal.DesensitizeSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 脱敏序列化行为测试（AC-004；架构设计 6.1、Q-008 结论、DP-010 定稿）：
 * 注解激活、未标记透传、策略缺失显式失败；逐策略掩码规则直测见 internal 包 DesensitizeMaskTest。
 */
class DesensitizeSerializationTest {

    private static JsonMapper mapper() {
        return JsonMapper.builder().addModules(JsonModules.standard()).build();
    }

    /** ① 标记 @Desensitize(PHONE) 字段输出掩码 138****5678（Q-008 结论）；属性名字母序（Modules.md 2.5.5）。 */
    @Test
    void phoneFieldIsMasked() {
        String json = mapper().writeValueAsString(new PhoneBean("13812345678", "张三"));
        assertEquals("{\"name\":\"张三\",\"phone\":\"138****5678\"}", json);
    }

    /** ② 未标记 @Desensitize 的字段原样输出。 */
    @Test
    void unmarkedFieldIsNotMasked() {
        String json = mapper().writeValueAsString(new PhoneBean("13812345678", "13812345678"));
        assertEquals("{\"name\":\"13812345678\",\"phone\":\"138****5678\"}", json);
    }

    /** ③ 策略缺失（序列化器被指定但无脱敏策略）显式失败（FR-025 异常分支；DP-010 定稿）。 */
    @Test
    void missingStrategyFailsExplicitly() {
        JsonMapper mapper = mapper();
        Exception e = assertThrows(Exception.class,
                () -> mapper.writeValueAsString(new StrategyMissingBean("13812345678")));
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertEquals("脱敏策略缺失，不得静默脱敏或静默放行", root.getMessage());
    }

    static class PhoneBean {
        @Desensitize(strategy = DesensitizeStrategy.PHONE)
        public String phone;
        public String name;

        PhoneBean() {
        }

        PhoneBean(String phone, String name) {
            this.phone = phone;
            this.name = name;
        }
    }

    /** 直接指定脱敏序列化器但无策略标记——策略缺失的可表达形态。 */
    static class StrategyMissingBean {
        @JsonSerialize(using = DesensitizeSerializer.class)
        public String phone;

        StrategyMissingBean() {
        }

        StrategyMissingBean(String phone) {
            this.phone = phone;
        }
    }
}
