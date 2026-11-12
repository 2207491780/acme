package com.acme.framework.jackson.config;

import com.acme.framework.jackson.internal.AcmeJsonMapperCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-010、AC-009/AC-010；NFR-001/NFR-005）。
 */
class JacksonSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                    JacksonAutoConfiguration.class));

    /** AC-009：关闭后 customizer 不装配，JsonMapper 为 Boot 缺省行为（与未引入一致，BR-006）。 */
    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.jackson.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AcmeJsonMapperCustomizer.class);
                    assertThat(context).doesNotHaveBean(JacksonProperties.class);
                    JsonMapper mapper = context.getBean(JsonMapper.class);
                    assertThat(mapper.writeValueAsString(123L)).isEqualTo("123");
                });
    }

    /** AC-010：零配置默认装配、功能可用。 */
    @Test
    void zeroConfigStartsWithDefaultsAndFullFunction() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AcmeJsonMapperCustomizer.class);
            JacksonProperties properties = context.getBean(JacksonProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(123L)).isEqualTo("\"123\"");
        });
    }
}
