package com.acme.framework.feign.config;

import com.acme.framework.feign.interceptor.ContextHeaderRequestInterceptor;
import com.acme.framework.feign.internal.FeignInvocationCapability;
import com.acme.framework.feign.internal.ResilientClientCapability;
import feign.Request;
import feign.Retryer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022）。
 */
class FeignSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.feign.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ContextHeaderRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean(FeignProperties.class);
                    assertThat(context).doesNotHaveBean(CircuitBreakerRegistry.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            FeignProperties properties = context.getBean(FeignProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getConnectTimeoutMillis()).isEqualTo(3000);
            assertThat(properties.getReadTimeoutMillis()).isEqualTo(5000);
            assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(1);
            assertThat(properties.getRetry().getBackoffInitialMillis()).isEqualTo(100);
            assertThat(properties.getRetry().getBackoffMultiplier()).isEqualTo(2);
            assertThat(properties.getRetry().getBackoffMaxMillis()).isEqualTo(1000);
            assertThat(properties.getCircuit().isEnabled()).isTrue();
            assertThat(properties.getCircuit().getFailureRateThreshold()).isEqualTo(50);
            assertThat(properties.getCircuit().getSlidingWindowSize()).isEqualTo(100);
            assertThat(properties.getCircuit().getWaitDurationInOpenStateSeconds()).isEqualTo(10);
            assertThat(properties.getCircuit().getPermittedCallsInHalfOpen()).isEqualTo(10);
            assertThat(properties.getToken().isEnabled()).isTrue();

            Request.Options options = context.getBean(Request.Options.class);
            assertThat(options.connectTimeoutMillis()).isEqualTo(3000);
            assertThat(options.readTimeoutMillis()).isEqualTo(5000);
            assertThat(context).hasSingleBean(Retryer.class);
            assertThat(context).getBeans(feign.Capability.class)
                    .hasSize(2)
                    .containsValue(context.getBean(ResilientClientCapability.class))
                    .containsValue(context.getBean(FeignInvocationCapability.class));
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.feign.connect-timeout-millis=1000",
                        "acme.feign.read-timeout-millis=2000",
                        "acme.feign.retry.max-attempts=4",
                        "acme.feign.circuit.enabled=false",
                        "acme.feign.token.enabled=false")
                .run(context -> {
                    FeignProperties properties = context.getBean(FeignProperties.class);
                    assertThat(properties.getConnectTimeoutMillis()).isEqualTo(1000);
                    assertThat(properties.getReadTimeoutMillis()).isEqualTo(2000);
                    assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(4);
                    assertThat(properties.getCircuit().isEnabled()).isFalse();
                    assertThat(properties.getToken().isEnabled()).isFalse();
                });
    }
}
