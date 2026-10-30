package com.acme.framework.jackson;

import com.acme.framework.jackson.config.JacksonAutoConfiguration;
import com.acme.framework.jackson.support.AcmeJacksonComponent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同类型注册冲突行为登记（FR-008 异常分支：遵循容器默认行为、不在本规格定制）。
 * 实测结论：注册不冲突失败、后注册者（@AcmeJacksonComponent 组件，customizer 后注册）覆盖同类型标准行为；
 * 本测试仅锁定实测行为防漂移，不代表规格承诺。
 */
class SameTypeConflictBehaviorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(LongConflictHolder.class);

    @Test
    void laterRegistrationOverridesSameTypeStandardBehavior() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(123L)).isEqualTo("\"conflict-long\"");
        });
    }

    @AcmeJacksonComponent(Long.class)
    static class LongConflictSerializer extends ValueSerializer<Long> {
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("conflict-long");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LongConflictHolder {
        @Bean
        LongConflictSerializer longConflictSerializer() {
            return new LongConflictSerializer();
        }
    }
}
