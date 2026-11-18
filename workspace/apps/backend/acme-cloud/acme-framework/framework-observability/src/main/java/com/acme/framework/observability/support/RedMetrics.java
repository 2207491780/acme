package com.acme.framework.observability.support;

import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * RED 记录门面（FR-019、AC-019、14.2.1）：Rate/Error/Duration 统一记录；
 * 采集失败不影响业务调用（NFR-005）；标签口径见 {@link MetricNames}。
 */
public class RedMetrics {

    private final MeterRegistry registry;
    private final String service;
    private final boolean red;

    public RedMetrics(MeterRegistry registry, String service, boolean red) {
        this.registry = registry;
        this.service = service == null ? "application" : service;
        this.red = red;
    }

    /** 记录一次调用（成功/失败）与耗时。 */
    public void record(String resource, boolean success, Duration duration) {
        if (!red) {
            return;
        }
        try {
            String status = success ? MetricNames.STATUS_SUCCESS : MetricNames.STATUS_ERROR;
            Tags tags = Tags.of(
                    MetricNames.TAG_SERVICE, service,
                    MetricNames.TAG_CHANNEL, channel(),
                    MetricNames.TAG_RESOURCE, resource == null ? "-" : resource,
                    MetricNames.TAG_STATUS, status);
            registry.counter(MetricNames.RED_REQUESTS, tags).increment();
            Timer.builder(MetricNames.RED_DURATION).tags(tags).register(registry)
                    .record(duration == null ? Duration.ZERO : duration);
        } catch (RuntimeException ex) {
            // 采集失败不影响业务调用（NFR-005）
        }
    }

    /** 记录一次成功调用与耗时。 */
    public void recordSuccess(String resource, Duration duration) {
        record(resource, true, duration);
    }

    /** 记录一次失败调用与耗时。 */
    public void recordError(String resource, Duration duration) {
        record(resource, false, duration);
    }

    private static String channel() {
        Channel channel = ClientContext.get() == null ? null : ClientContext.get().channel();
        return channel == null ? "-" : channel.name();
    }
}
