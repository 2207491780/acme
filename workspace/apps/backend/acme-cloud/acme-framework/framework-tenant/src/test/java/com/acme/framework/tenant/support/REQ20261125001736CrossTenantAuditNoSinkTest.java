package com.acme.framework.tenant.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.config.TenantAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * REQ-20261125-001736 AC-2（未装配任何 sink 场景）：框架可独立运行——未注册任何
 * CrossTenantAuditSink 时 record() 仅输出结构化日志、不报错。
 */
class REQ20261125001736CrossTenantAuditNoSinkTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class));

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        auditLogger.detachAppender(appender);
    }

    @Test
    void should_logWithoutError_when_noSinkAssembled() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        appender.start();
        auditLogger.addAppender(appender);

        runner.run(context -> {
            CrossTenantAuditLogger logger = context.getBean(CrossTenantAuditLogger.class);

            assertThatCode(() -> logger.record("Access#probe001736"))
                    .as("无 sink：仅日志不报错（框架可独立运行）").doesNotThrowAnyException();

            assertThat(appender.list).as("无 sink 时结构化日志仍输出").hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("Access#probe001736").contains("operator=system");
        });
    }
}
