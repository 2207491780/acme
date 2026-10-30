package com.acme.framework.audit.config;

import com.acme.framework.audit.aspect.AuditAspect;
import com.acme.framework.audit.support.AuditEventPublisher;
import com.acme.framework.audit.support.AuditEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004）。
 */
class AuditSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AuditAspect.class);
                    assertThat(context).doesNotHaveBean(AuditEventPublisher.class);
                    assertThat(context).doesNotHaveBean(AuditProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            AuditProperties properties = context.getBean(AuditProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getContentMaxLength()).isEqualTo(512);
            assertThat(properties.isMaskSensitive()).isTrue();
            assertThat(context).hasSingleBean(AuditEventPublisher.class);
        });
    }

    @Test
    void sinkIsForwarded() {
        ListProbe probe = new ListProbe();
        runner.withBean(AuditEventSink.class, () -> probe)
                .run(context -> {
                    AuditEventPublisher publisher = context.getBean(AuditEventPublisher.class);
                    publisher.publish(new com.acme.framework.audit.support.AuditEvent(
                            "e1", "t", "s", "tenant", "op", "B", "a", "T", "1", null,
                            java.time.Instant.now(), "test"));
                    assertThat(probe.seen).hasSize(1);
                });
    }

    static class ListProbe implements AuditEventSink {
        final java.util.List<com.acme.framework.audit.support.AuditEvent> seen = new java.util.ArrayList<>();

        @Override
        public void onEvent(com.acme.framework.audit.support.AuditEvent event) {
            seen.add(event);
        }
    }
}
