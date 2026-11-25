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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨租户审计结构化日志（FR-004/AC-004：traceId/操作人/租户/访问点关联）+
 * sink SPI 投递（REQ-20261125-001736 AC-2/AC-3：旁路 ROOT 与直读事件都投递、
 * 无 sink 仅日志不报错、sink 失败不阻断主流程）。
 */
class CrossTenantAuditLoggerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        UserContext.clear();
        TraceContext.clear();
        Logger logger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        logger.detachAppender(appender);
    }

    private void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    void recordCarriesCorrelationFields() {
        attachAppender();
        TraceContext.bind(new TraceContext("trace-1", "span-1"));
        UserContext.bind(new UserContext("u-1", Set.of("admin")));
        TenantContext.bind(new TenantContext("t-1"));

        new CrossTenantAuditLogger().record("AdminPlatform#listAll");

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("trace-1").contains("u-1").contains("t-1").contains("AdminPlatform#listAll");
    }

    @Test
    void recordFallsBackToSystemOperator() {
        attachAppender();
        new CrossTenantAuditLogger().record("Access#probe");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("operator=system");
    }

    // ---------- sink SPI（REQ-20261125-001736 AC-2/AC-3） ----------

    @Test
    void should_deliverBypassRootEvent_when_bypassActive() {
        attachAppender();
        RecordingSink sink = new RecordingSink();
        TraceContext.bind(new TraceContext("trace-bp", "span-bp"));
        UserContext.bind(new UserContext("admin-1", null));
        TenantContext.bind(new TenantContext("t-9"));

        try (TenantBypassScope ignored = TenantBypassScope.open("AdminPlatform#listAll")) {
            new CrossTenantAuditLogger(sink).record("AdminPlatform#listAll");
        }

        // 结构化日志照旧输出（tenant=* bypass=ROOT）
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=*").contains("bypass=ROOT").contains("trace-bp");

        // 事件自包含投递（旁路=ROOT、tenant=*，字段不依赖 ThreadLocal）
        assertThat(sink.events).hasSize(1);
        CrossTenantAuditEvent event = sink.events.get(0);
        assertThat(event.operator()).isEqualTo("admin-1");
        assertThat(event.tenantId()).isEqualTo("*");
        assertThat(event.accessPoint()).isEqualTo("AdminPlatform#listAll");
        assertThat(event.traceId()).isEqualTo("trace-bp");
        assertThat(event.bypass()).isEqualTo(CrossTenantAuditEvent.BYPASS_ROOT);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void should_deliverDirectReadEvent_when_tenantBoundWithoutBypass() {
        attachAppender();
        RecordingSink sink = new RecordingSink();
        TraceContext.bind(new TraceContext("trace-dir", null));
        TenantContext.bind(new TenantContext("t-1"));

        new CrossTenantAuditLogger(sink).record("CrossTenantProbe#read");

        assertThat(appender.list).hasSize(1);
        assertThat(sink.events).hasSize(1);
        CrossTenantAuditEvent event = sink.events.get(0);
        assertThat(event.operator()).isEqualTo("system");
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.accessPoint()).isEqualTo("CrossTenantProbe#read");
        assertThat(event.traceId()).isEqualTo("trace-dir");
        assertThat(event.bypass()).isEqualTo(CrossTenantAuditEvent.BYPASS_FALSE);
    }

    @Test
    void should_onlyLogWithoutError_when_noSinkConfigured() {
        attachAppender();
        TenantContext.bind(new TenantContext("t-1"));

        // 未装配 sink：仅日志、不报错（框架可独立运行）
        new CrossTenantAuditLogger().record("Access#probe");

        assertThat(appender.list).hasSize(1);
    }

    @Test
    void should_swallowSinkFailure_when_sinkThrows() {
        attachAppender();
        CrossTenantAuditLogger auditLogger = new CrossTenantAuditLogger(event -> {
            throw new IllegalStateException("sink down");
        });
        TenantContext.bind(new TenantContext("t-1"));

        // sink 投递失败不阻断主流程（AC-3：同步日志兜底，落库失败仅告警）
        auditLogger.record("Access#probe");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("Access#probe");
    }

    @Test
    void should_normalizeSelfContainedFields_when_eventBuiltDirectly() {
        Instant occurredAt = Instant.parse("2026-11-25T00:00:00Z");
        CrossTenantAuditEvent event = new CrossTenantAuditEvent(null, null, null, null, null, occurredAt);
        assertThat(event.operator()).isEqualTo("system");
        assertThat(event.tenantId()).isEqualTo("-");
        assertThat(event.accessPoint()).isEqualTo("-");
        assertThat(event.traceId()).isEqualTo("-");
        assertThat(event.bypass()).isEqualTo(CrossTenantAuditEvent.BYPASS_FALSE);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    /** 事件收集 sink（测试投递语义用）。 */
    private static final class RecordingSink implements CrossTenantAuditSink {

        private final List<CrossTenantAuditEvent> events = new ArrayList<>();

        @Override
        public void accept(CrossTenantAuditEvent event) {
            events.add(event);
        }
    }
}
