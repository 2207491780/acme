package com.acme.framework.observability.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.observability.internal.OtlpRegistryFactory;
import com.acme.framework.observability.support.RedMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * framework-observability 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 指标暴露面取 actuator {@code /actuator/metrics} 标准端点（取舍 #16，不自研端点）；
 * OTLP 导出配置面默认关（Q-007，{@code acme.observability.export.otlp.enabled}）；
 * 与 framework-trace 互补（14.1.2：traceId 归 trace，本模块管指标）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("observability")
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** RED 记录门面（FR-019）。 */
    @Bean
    public RedMetrics redMetrics(ObjectProvider<MeterRegistry> registries, Environment environment,
                                 ObservabilityProperties properties) {
        MeterRegistry registry = registries.getIfAvailable(CompositeMeterRegistry::new);
        String service = environment.getProperty("spring.application.name", "application");
        return new RedMetrics(registry, service, properties.isRed());
    }

    /** OTLP 导出装配（FR-020；仅显式开启接线，默认关，Q-007）。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.observability.export.otlp", name = "enabled", havingValue = "true")
    public OtlpMeterRegistry otlpMeterRegistry(ObservabilityProperties properties) {
        return OtlpRegistryFactory.create(properties.getExport().getOtlp());
    }
}
