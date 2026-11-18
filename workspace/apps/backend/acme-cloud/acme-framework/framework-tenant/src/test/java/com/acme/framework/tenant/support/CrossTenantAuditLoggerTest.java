package com.acme.framework.tenant.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨租户审计结构化日志（FR-004/AC-004：traceId/操作人/租户/访问点关联，不落库）。
 */
class CrossTenantAuditLoggerTest {

    private final CrossTenantAuditLogger auditLogger = new CrossTenantAuditLogger();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        UserContext.clear();
        TraceContext.clear();
        Logger logger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        logger.detachAppender(appender);
    }

    @Test
    void recordCarriesCorrelationFields() {
        Logger logger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        appender.start();
        logger.addAppender(appender);
        TraceContext.bind(new TraceContext("trace-1", "span-1"));
        UserContext.bind(new UserContext("u-1", Set.of("admin")));
        TenantContext.bind(new TenantContext("t-1"));

        auditLogger.record("AdminPlatform#listAll");

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("trace-1").contains("u-1").contains("t-1").contains("AdminPlatform#listAll");
    }

    @Test
    void recordFallsBackToSystemOperator() {
        Logger logger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        appender.start();
        logger.addAppender(appender);
        auditLogger.record("Access#probe");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("operator=system");
    }
}
