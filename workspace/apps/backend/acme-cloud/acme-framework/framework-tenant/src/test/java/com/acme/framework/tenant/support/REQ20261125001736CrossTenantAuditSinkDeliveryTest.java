package com.acme.framework.tenant.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.tenant.config.TenantAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001736 AC-2：CrossTenantAuditSink SPI 接线——旁路事件投递新增 sink，
 * 事件字段自包含（operator/tenantId/accessPoint/traceId/bypass/occurredAt，不依赖
 * ThreadLocal），同步结构化日志照旧输出。装配用 ApplicationContextRunner 挂
 * TenantAutoConfiguration（TenantSwitchTest 同缝：CrossTenantAuditLogger 为启用态 bean），
 * sink 以测试 bean 注册验证 Spring 装配接线（交付单测只覆盖直构造缝）。
 *
 * <p>口径说明（已随交付对齐，原并行假设销项）：</p>
 * <ul>
 *   <li>事件 bypass 字段为 String 常量：旁路 ROOT → {@code CrossTenantAuditEvent.BYPASS_ROOT}、
 *       非旁路 → {@code BYPASS_FALSE}（交付 CrossTenantAuditLoggerTest 同缝）。</li>
 *   <li>旁路激活时 tenantId 归一为 "*"（交付同缝）；结构化日志含 {@code tenant=*} 与 {@code bypass=ROOT}。</li>
 *   <li>投递时序：交付为 record 内同步投递；断言保留 3s 轮询等待（同步实现下立即满足，两态兼容）。</li>
 * </ul>
 */
class REQ20261125001736CrossTenantAuditSinkDeliveryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class))
            .withBean(RecordingSink.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        UserContext.clear();
        TraceContext.clear();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        auditLogger.detachAppender(appender);
    }

    @Test
    void should_deliverSelfContainedEventToSink_when_bypassRecorded() {
        runner.run(context -> {
            CrossTenantAuditLogger auditLogger = context.getBean(CrossTenantAuditLogger.class);
            RecordingSink sink = context.getBean(RecordingSink.class);

            UserContext.bind(new UserContext("u-001736", Set.of("admin")));
            TenantContext.bind(new TenantContext("t-001736"));
            TraceContext.bind(new TraceContext("trace-001736", "span-001736"));
            try (TenantBypassScope ignored = TenantBypassScope.open("req001736-bypass")) {
                auditLogger.record("AdminPlatform#listAll");
            }

            await(() -> !sink.events.isEmpty(), "旁路事件投递 sink");
            assertThat(sink.events).hasSize(1);
            CrossTenantAuditEvent event = sink.events.get(0);

            // 事件字段自包含：清理 ThreadLocal 后仍可完整回放（不依赖 ThreadLocal）
            UserContext.clear();
            TenantContext.clear();
            TraceContext.clear();
            assertThat(event.operator()).isEqualTo("u-001736");
            assertThat(event.tenantId()).as("旁路下租户归一为 *").isEqualTo("*");
            assertThat(event.accessPoint()).isEqualTo("AdminPlatform#listAll");
            assertThat(event.traceId()).isEqualTo("trace-001736");
            assertThat(event.bypass()).as("旁路激活态入事件（ROOT）").isEqualTo(CrossTenantAuditEvent.BYPASS_ROOT);
            assertThat(event.occurredAt()).as("时间戳自包含").isNotNull();
            assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
        });
    }

    @Test
    void should_emitStructuredLog_when_sinkWired() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        appender.start();
        auditLogger.addAppender(appender);

        runner.run(context -> {
            CrossTenantAuditLogger logger = context.getBean(CrossTenantAuditLogger.class);
            RecordingSink sink = context.getBean(RecordingSink.class);

            UserContext.bind(new UserContext("u-001736", Set.of("admin")));
            TenantContext.bind(new TenantContext("t-001736"));
            try (TenantBypassScope ignored = TenantBypassScope.open("req001736-bypass")) {
                logger.record("AdminPlatform#listAll");
            }

            await(() -> !sink.events.isEmpty(), "sink 投递");
            assertThat(appender.list).as("同步结构化日志照旧输出（SPI 不取代日志兜底）").hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).as("旁路日志口径（交付钉死）：tenant=* + bypass=ROOT + 关联字段")
                    .contains("tenant=*").contains("bypass=ROOT")
                    .contains("u-001736").contains("AdminPlatform#listAll");
        });
    }

    @Test
    void should_deliverDirectReadEventWithFalseBypass_when_tenantBoundWithoutBypass() {
        runner.run(context -> {
            CrossTenantAuditLogger logger = context.getBean(CrossTenantAuditLogger.class);
            RecordingSink sink = context.getBean(RecordingSink.class);

            // 非旁路直读事件：tenant 原值、bypass=FALSE，字段序逐一对齐
            TenantContext.bind(new TenantContext("t-target-001736"));
            TraceContext.bind(new TraceContext("tr-001736", "sp-001736"));
            logger.record("CrossTenant#probe");

            await(() -> !sink.events.isEmpty(), "直读事件投递 sink");
            assertThat(sink.events).hasSize(1);
            CrossTenantAuditEvent event = sink.events.get(0);
            assertThat(event.operator()).isEqualTo("system");
            assertThat(event.tenantId()).isEqualTo("t-target-001736");
            assertThat(event.accessPoint()).isEqualTo("CrossTenant#probe");
            assertThat(event.traceId()).isEqualTo("tr-001736");
            assertThat(event.bypass()).isEqualTo(CrossTenantAuditEvent.BYPASS_FALSE);
            // 事件 toString 字段齐全（自包含可回放）
            String formatted = event.toString();
            List<String> fieldOrder = List.of("system", "t-target-001736", "CrossTenant#probe", "tr-001736");
            int prev = -1;
            for (String field : fieldOrder) {
                int at = formatted.indexOf(field, prev + 1);
                assertThat(at).as("事件字段应含 %s（实况：%s）", field, formatted).isGreaterThanOrEqualTo(0);
                prev = at;
            }
        });
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待超时：" + what);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    /** 事件收集 sink（测试投递语义用）。 */
    static final class RecordingSink implements CrossTenantAuditSink {
        final List<CrossTenantAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(CrossTenantAuditEvent event) {
            events.add(event);
        }
    }
}
