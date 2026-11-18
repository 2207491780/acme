package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.framework.web.event.OpExceptionCapturedEvent;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import com.acme.platform.infra.infrastructure.redis.OpExceptionDedupStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-061017 异常日志中心 + 定时任务执行记录（platform-infra）服务域集成测试
 * （Testcontainers PG + Redis，装配口径照抄 {@code REQ20261114071607FileServiceIntegrationTest}
 * / {@code REQ20261114120104AuditScopeApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1（事件→sys_op_exception 异步落库＋同键 60s 去重）、AC-2（BizException 业务失败不落）、
 * AC-3（列表分页/筛选/详情含 stackSummary/缺权限统一拒绝）、AC-4（两个 retention job 硬删超期软删行、
 * 保留未超期）、AC-6（op-exceptions/export 与 audit-events/export 下载语义）、
 * AC-8（jobs/{jobKey}/executions 分页倒序＋手动触发 MANUAL 记录）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 {@code OpExceptionCapturedEvent}（framework-web 新事件类，并行开发未落地）：包名假设
 *       {@code com.acme.framework.web.event}，record 字段序假设
 *       {@code (service, traceId, spanId, tenantId, operatorId, method, path, statusCode,
 *       exceptionType, message, stackSummary, occurredAt)}（=变更清单列集）。交付字段/包名不同时，
 *       AC-1 三用例的构造调用同步调整。500 触发不走真实 translator 钩子（委派单允许事件面验证）；
 *       「响应不受影响」由发布路径异步语义保证，不另设断言。</li>
 *   <li>AMB-2 {@code sys_op_exception} 直插种子列集=变更清单列集＋AuditBase 通用列
 *       （created_at/created_by/updated_at/updated_by/version/deleted，acme_op_audit/sys_login_log
 *       V1 同型）；V18 迁移落地后列名如有出入，种子 SQL 同步调整。teardown 对该表物理清空。</li>
 *   <li>AMB-3 分页参数 {@code page}/{@code size}（REQ20261114120104AuditScopeApiIntegrationTest 先例）；
 *       筛选参数 service/traceId/exceptionType/from/to（契约 API 节字面量）；from/to 取 ISO-8601
 *       Instant 文本。wire Long→String：id 断言经 asText 比较。</li>
 *   <li>AMB-4 导出上限契约 10000 行；小上限截断测试经 {@code acme.excel.max-rows} 注入
 *       （framework-excel ExcelProperties.maxRows 既有键；契约将默认值口径调至 10000）。若交付为
 *       端点侧新属性/常量，{@code REQ20261117061017ExportTruncationIntegrationTest} 同步调整。</li>
 *   <li>AMB-5 导出下载语义：Content-Type=xlsx MIME、Content-Disposition=attachment＋filename* UTF-8
 *       （ExcelHttpExporter 先例）；X-Export-Truncated 非截断场景不断言为真值（缺失或 false 均可）；
 *       行数经 JDK ZipInputStream 解析 worksheet 行元素计数，断言=数据行+表头行 1 行
 *       （StreamExcelWriter「表头先行」先例）。</li>
 *   <li>AMB-6 去重键 service+exceptionType+path 60s（Redis SETNX TTL）：测试以同键二次发布＋异键
 *       第三次发布表达，不操纵 Redis TTL；二次发布后短暂驻留观察（1.2s）内不产生第二行。</li>
 *   <li>AMB-7 retention job key 字面量取确认②：exception-log-retention-cleanup / audit-retention-cleanup；
 *       默认保留 14/30 天；触发为异步（TaskTrigger），断言经行数轮询（finished_at 先例
 *       REQ20261114120105LoginLogCleanupIntegrationTest）。</li>
 *   <li>AMB-8 JobExecutionView wire 字段（jobKey/status/startedAt/finishedAt/error/triggerType/durationMs）
 *       双兼容取值；倒序断言经 startedAt 文本序（同序列化格式内可比）。</li>
 *   <li>堆栈摘要截断：AC-1 仅要求「截断发生」（存库长度严格小于输入长度，输入 1024），
 *       截断阈值数值契约未给（审计侧 512 先例），不作精确断言。</li>
 *   <li>权限拒绝口径：业务失败以 Result.code≠0 表达（勿断言 4xx；infra 域 requirePermission 桩件），
 *       照抄 {@code REQ20261114071607FileServiceIntegrationTest} AC-5。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req061017-opex-secret-0123456789",
        // 类内任务触发用例共享同一任务锁：归零锁最短持有（同 JobScheduleIntegrationTest 口径），
        // 否则上一用例完成后 60s 内的下一次手动触发会落 JOB_ALREADY_RUNNING
        "acme.job.lock-at-least-for=PT0S"
})
class REQ20261117061017OpExceptionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 异步落库/异步触发轮询上限（LoginLogRecorder 异步口径 + TaskTrigger 先例）。 */
    private static final Duration ASYNC_SETTLE = Duration.ofSeconds(10);

    /** 去重观察窗（同键二次发布后 60s 窗口内的短驻留；不操纵 Redis TTL，AMB-6）。 */
    private static final long DEDUP_OBSERVE_MILLIS = 1_200L;

    /** BizException 不落库的静默观察窗（若钩子误发事件，异步行应在此窗内出现）。 */
    private static final long SILENT_OBSERVE_MILLIS = 1_500L;

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：异常日志 60s 去重键（SETNX TTL）真实语义需要。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 本地存储落盘根（framework-storage 本地模式 bean 装配必填，缺失显式失败 SYS-15-0006）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req061017-opex-it-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 去重键清理面（@AfterEach 清 60s 去重窗口，方法间隔离——见类注 AMB 说明）。 */
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** ShedLock 行清理 + 内存注册表同步（同 JobScheduleIntegrationTest 口径：只删行不清缓存
     *  会让下一次获取跳过 INSERT 直走 UPDATE，0 行 → 误判锁被持）。 */
    @Autowired
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    private void clearShedLockState() {
        jdbcTemplate.update("delete from shedlock_locks");
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    /** 权限判定桩件：默认放行，缺权限用例内切换为拒绝。 */
    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：物理清表（V18 表 sys_op_exception + 审计底账 + 执行记录 + ShedLock 锁行/缓存）
        jdbcTemplate.update("delete from sys_op_exception");
        jdbcTemplate.update("delete from acme_op_audit");
        jdbcTemplate.update("delete from sys_job_execution");
        clearShedLockState();
        // 60s 去重窗口键清理（Redis 残留会让同键用例跨方法互相吞事件——REQ20261117-061017 实测教训）
        var dedupKeys = redisTemplate.keys(OpExceptionDedupStore.KEY_PREFIX + "*");
        if (dedupKeys != null && !dedupKeys.isEmpty()) {
            redisTemplate.delete(dedupKeys);
        }
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：未捕获 500 事件 → sys_op_exception 落一行（异步；堆栈摘要截断） ----------

    @Test
    void should_persistSingleOpException_when_eventPublishedForUnexpected500() {
        OpExceptionCapturedEvent event = new OpExceptionCapturedEvent(
                "platform-infra", "req061017-trace-1", "req061017-span-1", "t-1", "1",
                "GET", "/admin/v1/infra/files/99999", 500,
                "java.lang.IllegalStateException", "boom-req061017",
                longStack(1024), Instant.now()); // AMB-1 字段序假设

        eventPublisher.publishEvent(event);

        awaitTrue(ASYNC_SETTLE, () -> opExceptionCount() == 1L);

        Map<String, Object> row =
                jdbcTemplate.queryForMap("select service, trace_id, tenant_id, operator_id, path, "
                        + "status_code, exception_type, message, stack_summary from sys_op_exception");
        assertThat(row.get("trace_id")).isEqualTo("req061017-trace-1");
        assertThat(row.get("service")).isEqualTo("platform-infra");
        assertThat(row.get("tenant_id")).isEqualTo("t-1");
        assertThat(String.valueOf(row.get("operator_id"))).isEqualTo("1");
        assertThat(row.get("path")).isEqualTo("/admin/v1/infra/files/99999");
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(500);
        assertThat(row.get("exception_type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(row.get("message")).isEqualTo("boom-req061017");
        // 堆栈摘要发生截断（AC-1「堆栈摘要截断」）：存库长度严格小于输入长度（阈值数值不钉死）
        assertThat(String.valueOf(row.get("stack_summary")).length())
                .as("堆栈摘要应截断（存库长度 < 输入 1024）")
                .isLessThan(1024);
    }

    // ---------- AC-1：同键 60s 去重——同 service+exceptionType+path 二次发布不重复落 ----------

    @Test
    void should_notDuplicateRow_when_sameKeyEventRepublishedWithinDedupWindow() {
        eventPublisher.publishEvent(new OpExceptionCapturedEvent(
                "platform-infra", "req061017-dedup-1", null, "t-1", "1",
                "GET", "/admin/v1/infra/files/99999", 500,
                "java.lang.IllegalStateException", "boom", longStack(120), Instant.now()));
        awaitTrue(ASYNC_SETTLE, () -> opExceptionCount() == 1L);

        eventPublisher.publishEvent(new OpExceptionCapturedEvent(
                "platform-infra", "req061017-dedup-2", null, "t-1", "1",
                "GET", "/admin/v1/infra/files/99999", 500,
                "java.lang.IllegalStateException", "boom", longStack(120), Instant.now()));

        sleep(DEDUP_OBSERVE_MILLIS);
        assertThat(opExceptionCount())
                .as("同键（service+exceptionType+path）60s 内二次发布应去重，仅落一行").isEqualTo(1L);
    }

    // ---------- AC-1：去重键随 path 区分——异键发布正常落行 ----------

    @Test
    void should_persistSecondRow_when_eventKeyDiffersOnPath() {
        eventPublisher.publishEvent(new OpExceptionCapturedEvent(
                "platform-infra", "req061017-key-a", null, "t-1", "1",
                "GET", "/admin/v1/infra/files/99999", 500,
                "java.lang.IllegalStateException", "boom", longStack(120), Instant.now()));
        awaitTrue(ASYNC_SETTLE, () -> opExceptionCount() == 1L);

        eventPublisher.publishEvent(new OpExceptionCapturedEvent(
                "platform-infra", "req061017-key-b", null, "t-1", "1",
                "POST", "/admin/v1/infra/jobs/file-retention-cleanup/run", 500,
                "java.lang.IllegalStateException", "boom", longStack(120), Instant.now()));

        awaitTrue(ASYNC_SETTLE, () -> opExceptionCount() == 2L);
    }

    // ---------- AC-2：BizException 业务失败不落异常日志 ----------

    @Test
    void should_notPersistOpException_when_businessFailureInsteadOfSystem500() throws Exception {
        // 既有端点业务失败（BizException）：文件不存在 FILE_NOT_FOUND
        JsonNode fileMissing = call(get("/admin/v1/infra/files/{id}", "99999"));
        assertThat(fileMissing.path("success").asBoolean()).isFalse();
        assertThat(fileMissing.path("code").asText()).isEqualTo("FILE_NOT_FOUND");

        // 既有端点业务失败（BizException）：任务不存在 JOB_NOT_FOUND
        JsonNode jobMissing = call(post("/admin/v1/infra/jobs/{jobKey}/run", "req061017-no-job"));
        assertThat(jobMissing.path("success").asBoolean()).isFalse();

        sleep(SILENT_OBSERVE_MILLIS);
        assertThat(opExceptionCount())
                .as("BizException/业务失败非系统异常，不得落 sys_op_exception（AC-2）").isZero();
    }

    // ---------- AC-3：列表分页 + 筛选（service/traceId/exceptionType/from/to） ----------

    @Test
    void should_pageAndFilterOpExceptions_when_adminQueriesByConditions() throws Exception {
        seedOpException(910101, "platform-demo-a", "req061017-tr-a", "/demo/a/1",
                "java.lang.IllegalStateException", "now() - interval '120 minutes'");
        seedOpException(910102, "platform-demo-b", "req061017-tr-b", "/demo/b/1",
                "java.lang.NullPointerException", "now() - interval '60 minutes'");
        seedOpException(910103, "platform-demo-a", "req061017-tr-c", "/demo/a/2",
                "java.lang.IllegalStateException", "now() - interval '30 minutes'");

        // 全量分页：3 行
        List<JsonNode> all = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "50")));
        assertThat(all).hasSize(3);

        // service 筛选
        List<JsonNode> byService = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "50").param("service", "platform-demo-a")));
        assertThat(byService).hasSize(2);
        assertThat(servicesOf(byService)).containsOnly("platform-demo-a");

        // traceId 筛选
        List<JsonNode> byTrace = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "50").param("traceId", "req061017-tr-b")));
        assertThat(byTrace).hasSize(1);
        assertThat(textOf(byTrace.getFirst(), "traceId", "trace_id")).isEqualTo("req061017-tr-b");

        // exceptionType 筛选
        List<JsonNode> byType = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "50")
                .param("exceptionType", "java.lang.IllegalStateException")));
        assertThat(byType).hasSize(2);

        // from/to 时间窗（AMB-3 ISO-8601）：90 分钟前以来 → 2 行（排除 120 分钟前行）
        String from = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(90 * 60));
        String to = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(60));
        List<JsonNode> byWindow = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "50").param("from", from).param("to", to)));
        assertThat(byWindow).as("from/to 时间窗应只含 60/30 分钟前两行").hasSize(2);

        // 分页尺寸
        List<JsonNode> sized = recordList(call(get("/admin/v1/infra/op-exceptions")
                .param("page", "1").param("size", "2")));
        assertThat(sized).hasSize(2);
    }

    // ---------- AC-3：详情含 stackSummary 全文 ----------

    @Test
    void should_returnDetailWithStackSummary_when_queryById() throws Exception {
        String stack = "REQ061017-STACK-MARKER " + longStack(300);
        seedOpExceptionAt(910201, "platform-demo-a", "req061017-tr-detail", "/demo/detail",
                "java.lang.IllegalStateException", false, "now()", stack);

        JsonNode detail = call(get("/admin/v1/infra/op-exceptions/{exceptionId}", "910201"));

        assertThat(detail.path("code").asText()).isEqualTo("0");
        JsonNode data = detail.path("data");
        assertThat(data.path("id").asText()).as("wire Long→String（AMB-3）").isEqualTo("910201");
        assertThat(textOf(data, "stackSummary", "stack_summary"))
                .as("详情应含 stackSummary 全文（含种子标记）").contains("REQ061017-STACK-MARKER");
        assertThat(textOf(data, "service", "service")).isEqualTo("platform-demo-a");
        assertThat(textOf(data, "exceptionType", "exception_type"))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(textOf(data, "occurredAt", "occurred_at")).isNotBlank();

        // 边界：不存在 → 统一业务失败（错误码不钉死）
        JsonNode missing = call(get("/admin/v1/infra/op-exceptions/{exceptionId}", "999999"));
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(missing.path("code").asText()).isNotEqualTo("0");
    }

    // ---------- AC-3/AC-8：缺权限 → 统一拒绝（列表/详情/导出/执行记录/触发） ----------

    @Test
    void should_rejectUnifiedNonZero_when_exceptionPermissionMissing() throws Exception {
        reset(authorizationGateway);
        doThrow(new BizException(PERMISSION_DENIED)).when(authorizationGateway).requirePermission(anyString());

        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                get("/admin/v1/infra/op-exceptions"),
                get("/admin/v1/infra/op-exceptions/{exceptionId}", "910201"),
                get("/admin/v1/infra/op-exceptions/export"),
                get("/admin/v1/infra/audit-events/export"),
                get("/admin/v1/infra/jobs/{jobKey}/executions", "file-retention-cleanup"),
                post("/admin/v1/infra/jobs/{jobKey}/run", "exception-log-retention-cleanup"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = call(endpoint);
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint)
                    .isNotEqualTo("0");
        }
    }

    // ---------- AC-4：exception-log-retention-cleanup 硬删超期软删行、保留未超期 ----------

    @Test
    void should_hardDeleteExpiredSoftDeletedOpExceptions_when_exceptionRetentionJobRun() throws Exception {
        seedOpExceptionAt(910301, "platform-demo-a", "req061017-ret-1", "/demo/ret/1",
                "java.lang.IllegalStateException", true, "now() - interval '20 days'", "seed-stack");
        seedOpExceptionAt(910302, "platform-demo-a", "req061017-ret-2", "/demo/ret/2",
                "java.lang.IllegalStateException", true, "now() - interval '1 day'", "seed-stack");
        seedOpExceptionAt(910303, "platform-demo-a", "req061017-ret-3", "/demo/ret/3",
                "java.lang.IllegalStateException", false, "now() - interval '20 days'", "seed-stack");

        JsonNode triggered = call(post("/admin/v1/infra/jobs/{jobKey}/run",
                "exception-log-retention-cleanup"));
        assertThat(triggered.path("code").asText()).as("手动触发应受理成功").isEqualTo("0");

        awaitTrue(ASYNC_SETTLE, () -> softDeletedOpExceptionCount() == 1L);

        assertThat(opExceptionExists(910301))
                .as("deleted=true 且 updated_at 超期（20d > 14d）应被硬删").isFalse();
        assertThat(opExceptionExists(910302))
                .as("deleted=true 但未超期（1d < 14d）应保留").isTrue();
        assertThat(opExceptionExists(910303))
                .as("未软删的行不参与保留期硬删").isTrue();
    }

    // ---------- AC-4：audit-retention-cleanup 硬删超期软删审计行、保留未超期 ----------

    @Test
    void should_hardDeleteExpiredSoftDeletedAuditRows_when_auditRetentionJobRun() throws Exception {
        seedAuditRow(910401, "req061017-audit-exp", true, "now() - interval '40 days'");
        seedAuditRow(910402, "req061017-audit-keep", true, "now() - interval '5 days'");

        JsonNode triggered = call(post("/admin/v1/infra/jobs/{jobKey}/run",
                "audit-retention-cleanup"));
        assertThat(triggered.path("code").asText()).isEqualTo("0");

        awaitTrue(ASYNC_SETTLE, () -> softDeletedAuditCount() == 1L);

        assertThat(auditRowExists(910401))
                .as("deleted=true 且 updated_at 超期（40d > 30d）应被硬删").isFalse();
        assertThat(auditRowExists(910402))
                .as("deleted=true 但未超期（5d < 30d）应保留").isTrue();
    }

    // ---------- AC-6：op-exceptions/export 下载语义（xlsx 附件 + 行数） ----------

    @Test
    void should_exportOpExceptionsAsXlsxAttachment_when_adminDownloads() throws Exception {
        seedOpException(910501, "platform-demo-a", "req061017-exp-1", "/demo/exp/1",
                "java.lang.IllegalStateException", "now() - interval '10 minutes'");
        seedOpException(910502, "platform-demo-b", "req061017-exp-2", "/demo/exp/2",
                "java.lang.NullPointerException", "now() - interval '5 minutes'");

        MvcResult result = rawCall(get("/admin/v1/infra/op-exceptions/export"));

        assertXlsxDownload(result, 3); // 2 数据行 + 1 表头行（AMB-5）
        assertThat(result.getResponse().getHeader("X-Export-Truncated"))
                .as("未超上限不得携带截断真值（缺失或 false，AMB-5）").isNotEqualTo("true");

        // 筛选随行导出：service=platform-demo-a → 1 数据行 + 表头
        MvcResult filtered = rawCall(get("/admin/v1/infra/op-exceptions/export")
                .param("service", "platform-demo-a"));
        assertXlsxDownload(filtered, 2);
    }

    // ---------- AC-6：audit-events/export 下载语义（xlsx 附件 + 行数） ----------

    @Test
    void should_exportAuditEventsAsXlsxAttachment_when_adminDownloads() throws Exception {
        seedAuditRow(910601, "req061017-audit-exp-1", false, "now() - interval '10 minutes'");
        seedAuditRow(910602, "req061017-audit-exp-2", false, "now() - interval '5 minutes'");

        MvcResult result = rawCall(get("/admin/v1/infra/audit-events/export"));

        assertXlsxDownload(result, 3); // 2 数据行 + 1 表头行（AMB-5）
        assertThat(result.getResponse().getHeader("X-Export-Truncated"))
                .isNotEqualTo("true");
    }

    // ---------- AC-8：jobs/{jobKey}/executions 分页倒序 + 手动触发 MANUAL 记录 ----------

    @Test
    void should_listManualExecutionsDescending_when_jobRunTwiceAndQueried() throws Exception {
        triggerJobAndWait("file-retention-cleanup", 1);
        triggerJobAndWait("file-retention-cleanup", 2);

        JsonNode page = call(get("/admin/v1/infra/jobs/{jobKey}/executions", "file-retention-cleanup")
                .param("page", "1").param("size", "50"));

        assertThat(page.path("code").asText()).isEqualTo("0");
        List<JsonNode> records = recordList(page);
        assertThat(records).as("两次手动触发应产生两条执行记录").hasSize(2);
        for (JsonNode record : records) {
            assertThat(textOf(record, "jobKey", "job_key")).isEqualTo("file-retention-cleanup");
            assertThat(textOf(record, "triggerType", "trigger_type"))
                    .as("手动触发记录 trigger_type=MANUAL（AMB-8 双兼容）").isEqualTo("MANUAL");
            assertThat(textOf(record, "startedAt", "started_at")).isNotBlank();
        }
        // 倒序（started_at desc，AMB-8 文本序比较）
        String first = textOf(records.getFirst(), "startedAt", "started_at");
        String second = textOf(records.get(1), "startedAt", "started_at");
        assertThat(first.compareTo(second)).as("执行记录应按 started_at 倒序").isGreaterThanOrEqualTo(0);

        // 分页：size=1 → 单条
        List<JsonNode> sized = recordList(call(get("/admin/v1/infra/jobs/{jobKey}/executions",
                "file-retention-cleanup").param("page", "1").param("size", "1")));
        assertThat(sized).hasSize(1);
    }

    // ---------- 支撑 ----------

    /** AC-缺权限桩件错误码（真实拒绝码由安全层定义，用例仅断言 Result.code≠0 语义）。 */
    private static final ErrorCode PERMISSION_DENIED = new ErrorCode() {
        @Override
        public String code() {
            return "PERMISSION_DENIED";
        }

        @Override
        public String defaultMessage() {
            return "无权限（集成测试桩）";
        }
    };

    private static String longStack(int length) {
        return ("at com.acme.demo.StackFrame.handle(StackFrame.java:1)" + "\n").repeat(20)
                .substring(0, Math.min(length, 1100));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("观察窗等待被中断", ex);
        }
    }

    private long opExceptionCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_op_exception", Long.class);
        return count == null ? 0L : count;
    }

    private long softDeletedOpExceptionCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_op_exception where deleted = true", Long.class);
        return count == null ? 0L : count;
    }

    private boolean opExceptionExists(long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_op_exception where id = ?", Long.class, id);
        return count != null && count > 0;
    }

    private long softDeletedAuditCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from acme_op_audit where deleted = true", Long.class);
        return count == null ? 0L : count;
    }

    private boolean auditRowExists(long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from acme_op_audit where id = ?", Long.class, id);
        return count != null && count > 0;
    }

    /** 直插 sys_op_exception（AMB-2 列集；occurred_at 由 SQL 时间表达式表达）。 */
    private void seedOpException(long id, String service, String traceId, String path,
                                 String exceptionType, String occurredExpr) {
        seedOpExceptionAt(id, service, traceId, path, exceptionType, false, occurredExpr, "seed-stack-summary");
    }

    private void seedOpExceptionAt(long id, String service, String traceId, String path,
                                   String exceptionType, boolean deleted, String occurredExpr,
                                   String stackSummary) {
        jdbcTemplate.update("""
                insert into sys_op_exception (id, service, trace_id, span_id, tenant_id, operator_id,
                                              method, path, status_code, exception_type, message,
                                              stack_summary, occurred_at,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'req061017-span', 't-1', '1',
                        'GET', ?, 500, ?, 'seed-message',
                        ?, %s,
                        now(), 'system', %s, 'system', 0, ?)
                """.formatted(occurredExpr, occurredExpr),
                id, service, traceId, path, exceptionType, stackSummary, deleted);
    }

    /** 直插 acme_op_audit（V1 列集；deleted 可控，用于保留期种子与导出行数）。 */
    private void seedAuditRow(long id, String eventId, boolean deleted, String occurredExpr) {
        jdbcTemplate.update("""
                insert into acme_op_audit (id, event_id, trace_id, tenant_id, operator_id, channel,
                                           action, content, occurred_at,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 'req061017-audit-trace', 't-1', '1', 'B',
                        'demo.audit', 'seed-content', %s,
                        now(), 'system', %s, 'system', 0, ?)
                """.formatted(occurredExpr, occurredExpr),
                id, eventId, deleted);
    }

    /** 手动触发任务并轮询等待执行完成（异步 TaskTrigger，AMB-7）。 */
    private void triggerJobAndWait(String jobKey, int expectedExecutions) throws Exception {
        // 触发 + JOB_ALREADY_RUNNING 短窗重试（类内跨用例锁释放竞态容忍；PT0S 下窗口毫秒级闭合）
        long deadline = System.nanoTime() + ASYNC_SETTLE.toNanos();
        JsonNode triggered = call(post("/admin/v1/infra/jobs/{jobKey}/run", jobKey));
        while ("JOB_ALREADY_RUNNING".equals(triggered.path("code").asText(null))
                && System.nanoTime() < deadline) {
            Thread.sleep(200L);
            triggered = call(post("/admin/v1/infra/jobs/{jobKey}/run", jobKey));
        }
        assertThat(triggered.path("code").asText()).as("手动触发应受理成功").isEqualTo("0");
        awaitTrue(ASYNC_SETTLE, () -> {
            Long finished = jdbcTemplate.queryForObject(
                    "select count(*) from sys_job_execution "
                            + "where job_key = ? and finished_at is not null",
                    Long.class, jobKey);
            return finished != null && finished >= expectedExecutions;
        });
    }

    private void assertXlsxDownload(MvcResult result, int expectedTotalRows) throws IOException {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentType()).contains(XLSX_MIME);
        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment").contains("filename*=UTF-8''");
        assertThat(bytes[0]).as("xlsx 应为 zip 容器（PK 魔数）").isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        assertThat(xlsxRowCount(bytes))
                .as("导出行数（数据行+表头行，AMB-5）应为 %d", expectedTotalRows).isEqualTo(expectedTotalRows);
    }

    /** xlsx 行元素计数（按 central directory 读 worksheet XML，不引新依赖）。
     *  不用 ZipInputStream：FastExcel/POI 写出的条目带 data descriptor（local header size=0、
     *  flags=0x8），JDK 25 ZipInputStream 对该布局误报 invalid entry size——产物本身
     *  Excel/ZipFile 均可正常打开，属流式解析器兼容面缺陷，故走临时文件 + ZipFile 解析。 */
    private static int xlsxRowCount(byte[] bytes) throws IOException {
        int rows = 0;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("req061017-opex-export", ".xlsx");
        try {
            java.nio.file.Files.write(temp, bytes);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(temp.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml")) {
                        String xml = new String(zip.getInputStream(entry).readAllBytes(),
                                StandardCharsets.UTF_8);
                        int index = 0;
                        while ((index = xml.indexOf("<row", index)) >= 0) {
                            rows++;
                            index += 4;
                        }
                    }
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
        assertThat(rows).as("xlsx 应含 worksheet 行").isGreaterThan(0);
        return rows;
    }

    /** 分页数据定位：data 数组 / {records} 双兼容 → 列表（AssertJ 断言友好）。 */
    private static List<JsonNode> recordList(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode records = data.isArray() ? data : data.path("records");
        JsonNode source = records.isMissingNode() ? data : records;
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : source) {
            nodes.add(node);
        }
        return nodes;
    }

    private static List<String> servicesOf(List<JsonNode> records) {
        return records.stream()
                .map(record -> textOf(record, "service", "service"))
                .toList();
    }

    /** wire 字段双兼容取值（驼峰/下划线）。 */
    private static String textOf(JsonNode node, String camel, String snake) {
        if (node.hasNonNull(camel)) {
            return node.get(camel).asText();
        }
        if (node.hasNonNull(snake)) {
            return node.get(snake).asText();
        }
        return null;
    }

    /** 信封请求（默认放行桩件下）；返回 Result 信封 JSON。 */
    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject("1", "t-1", Channel.B, Set.of("admin"))))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    /** 下载语义请求：返回 MvcResult（字节/响应头断言）。 */
    private MvcResult rawCall(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mvc.perform(request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject("1", "t-1", Channel.B, Set.of("admin"))))
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("轮询被中断", ex);
            }
        }
        org.assertj.core.api.Assertions.fail("等待条件超时（%s）", timeout);
    }
}
