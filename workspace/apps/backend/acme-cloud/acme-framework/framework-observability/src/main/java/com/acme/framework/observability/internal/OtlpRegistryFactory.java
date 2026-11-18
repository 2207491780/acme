package com.acme.framework.observability.internal;

import com.acme.framework.observability.config.ObservabilityProperties;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;

import java.time.Duration;

/**
 * OTLP 导出装配（FR-020、AC-020、Q-007：单测验证装配；默认关；不起 Collector、不做导出链路实证）。
 * 启用而端点缺失启动期显式失败（IllegalStateException，架构设计 12.3）；
 * 端点不可用不阻塞业务（导出侧缓冲/丢弃由 OtlpMeterRegistry 内建）。
 */
public final class OtlpRegistryFactory {

    private OtlpRegistryFactory() {
    }

    /**
     * 构造 OTLP 注册表（仅导出配置面）。
     *
     * @throws IllegalStateException 启用但端点缺失（配置非法，启动期显式失败）
     */
    public static OtlpMeterRegistry create(ObservabilityProperties.Otlp otlp) {
        if (otlp.getEndpoint() == null || otlp.getEndpoint().isBlank()) {
            throw new IllegalStateException("OTLP 导出启用但端点缺失（acme.observability.export.otlp.endpoint）");
        }
        String endpoint = otlp.getEndpoint();
        Duration step = otlp.getStep() == null ? Duration.ofSeconds(60) : otlp.getStep();
        OtlpConfig config = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return endpoint;
            }

            @Override
            public Duration step() {
                return step;
            }
        };
        return OtlpMeterRegistry.builder(config).build();
    }
}
