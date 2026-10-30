package com.acme.framework.stream.internal;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费重试接线（FR-015、10.3.3）：acme.stream.retry.* 映射为各 consumer 绑定的退避参数。
 */
class RetryBindingDefaultsTest {

    private final RetryBindingDefaults postProcessor = new RetryBindingDefaults();

    @Test
    void mapsRetryDefaultsToInboundBindings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("acme.stream.retry.max-attempts", "5")
                .withProperty("acme.stream.retry.backoff-initial-millis", "200")
                .withProperty("acme.stream.retry.backoff-multiplier", "3")
                .withProperty("acme.stream.retry.backoff-max-millis", "4000")
                .withProperty("spring.cloud.stream.bindings.order-in-0.destination", "order-topic")
                .withProperty("spring.cloud.function.definition", "orderHandler");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.max-attempts"))
                .isEqualTo("5");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.back-off-initial-interval"))
                .isEqualTo("200");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.back-off-multiplier"))
                .isEqualTo("3");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.back-off-max-interval"))
                .isEqualTo("4000");
        // function.definition 推导绑定同样接线
        assertThat(environment.getProperty("spring.cloud.stream.bindings.orderHandler-in-0.consumer.max-attempts"))
                .isEqualTo("5");
    }

    @Test
    void explicitPerBindingConfigStillWins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.stream.bindings.order-in-0.destination", "order-topic")
                .withProperty("spring.cloud.stream.bindings.order-in-0.consumer.max-attempts", "9");
        postProcessor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.max-attempts"))
                .as("per-binding 显式配置优先").isEqualTo("9");
    }

    @Test
    void disabledModuleDoesNotWire() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("acme.stream.enabled", "false")
                .withProperty("spring.cloud.stream.bindings.order-in-0.destination", "order-topic");
        postProcessor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.bindings.order-in-0.consumer.max-attempts"))
                .isNull();
    }

    @Test
    void defaultValuesMatchStreamPropertiesDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.stream.bindings.a-in-0.destination", "t");
        postProcessor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.bindings.a-in-0.consumer.max-attempts")).isEqualTo("3");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.a-in-0.consumer.back-off-initial-interval"))
                .isEqualTo("100");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.a-in-0.consumer.back-off-multiplier"))
                .isEqualTo("2");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.a-in-0.consumer.back-off-max-interval"))
                .isEqualTo("2000");
    }

    @Test
    void idempotentPostProcessDoesNotDuplicateSource() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.stream.bindings.a-in-0.destination", "t");
        postProcessor.postProcessEnvironment(environment, null);
        postProcessor.postProcessEnvironment(environment, null);
        long count = java.util.stream.StreamSupport
                .stream(environment.getPropertySources().spliterator(), false)
                .filter(source -> source instanceof MapPropertySource map
                        && "acmeStreamRetryBindingDefaults".equals(map.getName()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
