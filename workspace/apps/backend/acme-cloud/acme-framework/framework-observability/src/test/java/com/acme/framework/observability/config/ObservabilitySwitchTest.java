package com.acme.framework.observability.config;

import com.acme.framework.observability.internal.OtlpRegistryFactory;
import com.acme.framework.observability.support.MetricNames;
import com.acme.framework.observability.support.RedMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块开关、RED 记录与 OTLP 装配测试（FR-019/020/AC-019/020、Q-007、BR-003/004）。
 */
class ObservabilitySwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedMetrics.class);
                    assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
                    assertThat(context).doesNotHaveBean(ObservabilityProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaultsAndOtlpOff() {
        runner.run(context -> {
            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isRed()).isTrue();
            assertThat(properties.isJvm()).isTrue();
            assertThat(properties.isPool()).isTrue();
            assertThat(properties.getExport().getOtlp().isEnabled()).isFalse();
            assertThat(properties.getExport().getOtlp().getStep()).isEqualTo(Duration.ofSeconds(60));
            assertThat(context).hasSingleBean(RedMetrics.class);
            // 默认关：无 OTLP 注册表（Q-007）
            assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.observability.red=false",
                        "acme.observability.jvm=false",
                        "acme.observability.pool=false",
                        "acme.observability.export.otlp.enabled=true",
                        "acme.observability.export.otlp.endpoint=http://localhost:4317",
                        "acme.observability.export.otlp.step=30s")
                .run(context -> {
                    ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
                    assertThat(properties.isRed()).isFalse();
                    assertThat(properties.getExport().getOtlp().isEnabled()).isTrue();
                    assertThat(properties.getExport().getOtlp().getStep()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
                });
    }

    @Test
    void redMetricsRecordsRateErrorDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedMetrics metrics = new RedMetrics(registry, "p5-sample", true);
        metrics.recordSuccess("res", Duration.ofMillis(12));
        metrics.recordError("res", Duration.ofMillis(30));
        assertThat(registry.find(MetricNames.RED_REQUESTS).counters())
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(registry.find(MetricNames.RED_DURATION).timers()).isNotEmpty();
    }

    @Test
    void redSwitchOffRecordsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedMetrics metrics = new RedMetrics(registry, "p5-sample", false);
        metrics.recordSuccess("res", Duration.ofMillis(1));
        assertThat(registry.find(MetricNames.RED_REQUESTS).counters()).isEmpty();
    }

    @Test
    void otlpWithoutEndpointFailsExplicitly() {
        assertThatThrownBy(() -> OtlpRegistryFactory.create(new ObservabilityProperties.Otlp()))
                .isInstanceOf(IllegalStateException.class);
    }
}
