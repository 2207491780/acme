package com.acme.platform.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.support.CrossTenantAuditEvent;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.CrossTenantAuditSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * REQ-20261125-001736 AC-3：platform-infra DB sink 旁路审计落库链路（Testcontainers PostgreSQL，
 * 装配口径照抄 {@link InfraIntegrationTest}；无 Docker 自动跳过）——旁路事件投递落
 * {@code sys_cross_tenant_audit}（operator/tenant_id/access_point/trace_id/bypass/created_at，
 * 索引 operator+created_at）；异步写失败 → ERROR 告警日志并丢弃该行，主流程零影响；
 * 同步结构化日志不受影响。
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 sink 获取假设：platform-infra 将 DB sink 注册为 CrossTenantAuditSink 类型 bean
 *       （变更清单「DB sink 实现」），按类型注入唯一 bean。</li>
 *   <li>AMB-2 异步时序假设「异步尽力落库」（确认记录③）：断言 3s 轮询等待；同步实现下等待立即满足。</li>
 *   <li>AMB-3 索引名未钉死：按 pg_indexes 列定义断言（operator 与 created_at 同索引）。</li>
 *   <li>AMB-4 写失败注入不读实现/迁移内容：RENAME COLUMN operator → 插入必失败（列名钉死于 AC-3），
 *       finally 复原；ERROR 告警 logger 名未钉死 → 挂根 logger 按 Level.ERROR 过滤。</li>
 * </ul>
 */
class REQ20261125001736CrossTenantAuditDbSinkIntegrationTest extends AbstractInfraIntegrationTest {

    /** 本地盘存储根（服务装配含 framework-storage；type=local 缺省，root-dir 必配，InfraIntegrationTest 同口径）。 */
    private static final java.nio.file.Path SHARED_STORAGE_ROOT = createStorageRoot();

    private static java.nio.file.Path createStorageRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("audit-sink-it-storage");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @Autowired
    private CrossTenantAuditSink auditSink;

    @Autowired
    private CrossTenantAuditLogger auditLogger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ListAppender<ILoggingEvent> errorEvents = new ListAppender<>();
    private final ListAppender<ILoggingEvent> syncAuditEvents = new ListAppender<>();

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：旁路审计行全清 + 上下文/挂载复原
        jdbcTemplate.update("delete from sys_cross_tenant_audit");
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(errorEvents);
        ((Logger) LoggerFactory.getLogger("acme.tenant.audit")).detachAppender(syncAuditEvents);
        TenantContext.clear();
    }

    // ---------- AC-3：旁路事件落 sys_cross_tenant_audit ----------

    @Test
    void should_persistBypassEventRow_when_sinkAccepts() {
        auditSink.accept(new CrossTenantAuditEvent(
                "u-001736", "*", "AdminPlatform#listAll", "trace-001736",
                CrossTenantAuditEvent.BYPASS_ROOT, Instant.now()));

        List<Map<String, Object>> rows = awaitRows(
                "select operator, tenant_id, access_point, trace_id, bypass, created_at "
                        + "from sys_cross_tenant_audit where access_point = 'AdminPlatform#listAll'",
                "旁路事件异步落库");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("operator")).isEqualTo("u-001736");
        assertThat(row.get("tenant_id")).as("旁路行 tenant=*（R3 钉死）").isEqualTo("*");
        assertThat(row.get("access_point")).isEqualTo("AdminPlatform#listAll");
        assertThat(row.get("trace_id")).isEqualTo("trace-001736");
        assertThat(row.get("bypass")).as("bypass=ROOT 字符串标记（R3 钉死，与同步日志一致）")
                .isEqualTo(CrossTenantAuditEvent.BYPASS_ROOT);
        assertThat(row.get("created_at")).as("落库时间戳必填").isNotNull();
    }

    @Test
    void should_indexOperatorWithCreatedAt_when_migrationApplied() {
        List<String> indexDefs = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where tablename = 'sys_cross_tenant_audit'", String.class);
        assertThat(indexDefs).as("sys_cross_tenant_audit 索引齐备（含 operator+created_at 组合）")
                .isNotEmpty()
                .anySatisfy(def -> assertThat(def).contains("operator").contains("created_at"));
    }

    // ---------- AC-3：异步写失败 → ERROR 告警 + 丢弃该行 + 主流程/同步日志零影响 ----------

    @Test
    void should_dropFailedRowAndAlertWithoutBlockingMainFlow_when_asyncWriteFails() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        // 局部 logback logger 改名避开 @Autowired CrossTenantAuditLogger 字段 auditLogger（原名遮蔽致 record(...) 编译错）
        Logger auditEventLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        errorEvents.start();
        rootLogger.addAppender(errorEvents);
        syncAuditEvents.start();
        auditEventLogger.addAppender(syncAuditEvents);

        try {
            // 写失败注入（AMB-4）：列名钉死于 AC-3，RENAME 后插入必失败；finally 复原
            jdbcTemplate.execute("alter table sys_cross_tenant_audit rename column operator to operator_broken_001736");

            // 主流程零影响：record/投递路径不因写库失败抛出（可用性优先于审计强一致）
            assertThatCode(() -> auditLogger.record("Drop#probe001736")).doesNotThrowAnyException();

            // ERROR 告警日志出现（AMB-4：logger 名未钉死，挂根 logger 按 ERROR 过滤）
            await(() -> errorEvents.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR),
                    "异步写失败 ERROR 告警日志");
            // 同步结构化日志不受影响（兜底照旧；未绑定上下文 → operator=system 既有口径）
            await(() -> !syncAuditEvents.list.isEmpty(), "同步结构化日志兜底");
            assertThat(syncAuditEvents.list.get(0).getFormattedMessage())
                    .contains("Drop#probe001736").contains("operator=system");
        } finally {
            jdbcTemplate.execute("alter table sys_cross_tenant_audit rename column operator_broken_001736 to operator");
        }

        // 丢弃该行：失败事件不回填（无重试），修复后仍不应出现
        sleepQuietly(1500);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_cross_tenant_audit where access_point = 'Drop#probe001736'", Long.class))
                .as("异步写失败的行应被丢弃（不重试落库）").isZero();
    }

    // ---------- AC-3：同步日志兜底与落库并存（记录③修订口径） ----------

    @Test
    void should_keepStructuredLogAlongsidePersistence_when_sinkWired() {
        Logger auditEventLogger = (Logger) LoggerFactory.getLogger("acme.tenant.audit");
        syncAuditEvents.start();
        auditEventLogger.addAppender(syncAuditEvents);

        TenantContext.bind(new TenantContext("t-001736"));
        try {
            auditLogger.record("LogAndPersist#probe001736");

            await(() -> !jdbcTemplate.queryForList(
                    "select 1 from sys_cross_tenant_audit where access_point = 'LogAndPersist#probe001736'",
                    Long.class).isEmpty(), "旁路事件落库");
            assertThat(syncAuditEvents.list).as("同步结构化日志与落库并存（修订 Q-005）").hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }

    // ---------- 支撑 ----------

    private List<Map<String, Object>> awaitRows(String sql, String what) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (!rows.isEmpty()) {
                return rows;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待超时：" + what);
            }
            sleepQuietly(25);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待超时：" + what);
            }
            sleepQuietly(25);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
