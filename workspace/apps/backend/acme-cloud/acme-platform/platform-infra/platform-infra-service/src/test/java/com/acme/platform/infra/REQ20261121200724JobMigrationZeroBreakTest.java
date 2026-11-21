package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261121-200724（JOB-2）存量任务迁移零破坏集成测试（platform-infra 服务面）；
 * 装配照抄 {@code REQ20261114071607JobScheduleIntegrationTest} 先例（Testcontainers PG+Redis；
 * 无 Docker 自动跳过——判定口径 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-6（服务面）与 AC-8：</p>
 * <ul>
 *   <li>AC-8：4 任务迁移后 /admin/v1/infra/jobs 列表全量在位（TaskRegistry 兼容视图，
 *       JobUseCase.list 消费面零破坏）；手动触发照常受理且任务实际执行；
 *       执行历史 /admin/v1/infra/jobs/{jobKey}/executions 分页照常（双写历史零破坏）。</li>
 *   <li>AC-6 服务面：本地 trigger 照旧落 sys_job_execution（JobExecutionRecorder 双写，
 *       triggerType 归一 TRIGGER_MANUAL——契约确认⑦），任务本体保留期语义照常执行。</li>
 * </ul>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>jobKey 沿用现 taskId 值（契约确认⑦钉死：file/login-log/exception-log/audit
 *       retention-cleanup）。</li>
 *   <li>sys_job_execution trigger 取值按确认⑦归一枚举断言 TRIGGER_MANUAL；status 只断言
 *       非空（成功/失败字面量契约未钉死）。</li>
 *   <li>手动触发同步性契约未指明：轮询断言（10s 上限），同步/异步实现均兼容。</li>
 *   <li>迁移前后（@AcmeTask→@JobHandler 注解替换）本类断言面等价——零破坏回归口径。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-job2-it-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme",
        "acme.storage.upload.retention-days=1",
        // 类内多触发用例共享同一任务锁：lockAtLeastFor 归零防下一用例落 JOB_ALREADY_RUNNING（范本先例）
        "acme.job.lock-at-least-for=PT0S"
})
class REQ20261121200724JobMigrationZeroBreakTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 4 个存量保留期清理任务 jobKey（契约确认⑦：jobKey 沿用现 taskId 值）。 */
    private static final List<String> MIGRATED_JOB_KEYS = List.of(
            "file-retention-cleanup",
            "login-log-retention-cleanup",
            "exception-log-retention-cleanup",
            "audit-retention-cleanup");

    private static final String JOB_KEY = "file-retention-cleanup";
    private static final Duration TRIGGER_SETTLE = Duration.ofSeconds(10);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req20261121200724-job-it");
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
    private JobLockKeys jobLockKeys;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void cleanUp() throws IOException {
        // teardown 清共享脏数据：物理清表（含 ShedLock 锁行 + 内存注册表同步）+ 清空本地盘对象
        jdbcTemplate.update("delete from sys_file");
        jdbcTemplate.update("delete from sys_job_execution");
        clearShedLockState();
        clearStorageRoot();
        TenantContext.clear();
        UserContext.clear();
    }

    /** ShedLock 锁行清理 + 内存缓存同步（范本口径：只删行不清缓存会误判锁被持）。 */
    private void clearShedLockState() {
        jdbcTemplate.update("delete from shedlock_locks");
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    // ---------- AC-8①：列表零破坏（TaskRegistry 兼容视图含全部 4 迁移任务） ----------

    @Test
    void should_list_all_four_migrated_jobs_with_descriptors_when_query_job_registry()
            throws Exception {
        JsonNode body = bodyOf(call(get("/admin/v1/infra/jobs")));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.isArray()).as("列表应为数组（JobUseCase.list 兼容视图）").isTrue();

        List<String> listedKeys = new ArrayList<>();
        for (JsonNode item : data) {
            listedKeys.add(textOf(item, "jobKey", "key"));
        }
        for (String expected : MIGRATED_JOB_KEYS) {
            assertThat(listedKeys).as("迁移任务 %s 应在 /admin/v1/infra/jobs 列表在位（零破坏）", expected)
                    .contains(expected);
        }

        JsonNode target = data.get(0);
        for (JsonNode item : data) {
            if (JOB_KEY.equals(textOf(item, "jobKey", "key"))) {
                target = item;
            }
        }
        assertThat(textOf(target, "cron")).as("cron 描述应非空").isNotBlank();
        assertThat(target.hasNonNull("enabled")).as("enabled 字段应在注册表中返回").isTrue();
    }

    // ---------- AC-8②/AC-6：手动触发零破坏（受理 + sys_job_execution 双写 + 任务实际执行） ----------

    @Test
    void should_record_dual_write_execution_and_execute_task_when_trigger_migrated_job_manually()
            throws Exception {
        seedSysFile(910001L, "req-job2/2026/11/21/expired-a.txt", "expired-a.txt", true,
                "now() - interval '2 days'");
        writeObject("req-job2/2026/11/21/expired-a.txt", "expired-a-content");

        JsonNode body = triggerRunWithRetry();

        assertThat(body.path("code").asText()).as("手动触发应受理成功").isEqualTo("0");
        assertThat(body.path("data").isNull()).as("手动触发应返回执行记录 id").isFalse();
        assertThat(body.path("data").asText()).isNotBlank();

        // sys_job_execution 双写照旧（JobExecutionRecorder 本地面；契约确认⑥双轨兼容）
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> executionCount() > 0))
                .as("sys_job_execution 应落执行记录（双写零破坏）").isTrue();
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> latestExecution().get("finished_at") != null))
                .as("执行完成后应回填 finished_at").isTrue();
        Map<String, Object> done = latestExecution();
        assertThat(String.valueOf(done.get("job_key"))).isEqualTo(JOB_KEY);
        assertThat(String.valueOf(done.get("status"))).as("status 应非空（字面量不钉死）").isNotBlank();
        assertThat(done.get("trigger_type") == null ? null : String.valueOf(done.get("trigger_type")))
                .as("triggerType 应归一 TRIGGER_MANUAL（契约确认⑦）").isEqualTo("TRIGGER_MANUAL");
        assertThat(done.get("error")).as("成功执行 error 应为空").isNull();

        // 任务本体保留期语义照常：软删超期文件被清理（记录 + 对象）
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> sysFileCount(910001L) == 0))
                .as("软删超期文件记录应被清理（任务本体语义零破坏）").isTrue();
        assertThat(objectExists("req-job2/2026/11/21/expired-a.txt")).isFalse();
    }

    // ---------- AC-8③：执行历史零破坏（分页 + 字段面） ----------

    @Test
    void should_page_execution_history_when_migrated_job_ran_twice() throws Exception {
        JsonNode first = triggerRunWithRetry();
        assertThat(first.path("code").asText()).isEqualTo("0");
        clearShedLockState();
        JsonNode second = triggerRunWithRetry();
        assertThat(second.path("code").asText()).isEqualTo("0");
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> executionCount() >= 2))
                .as("两次手动触发应各落一条执行记录").isTrue();

        JsonNode page = bodyOf(call(get("/admin/v1/infra/jobs/{jobKey}/executions", JOB_KEY)
                .param("page", "1").param("size", "50")));
        assertThat(page.path("code").asText()).isEqualTo("0");
        List<JsonNode> records = recordList(page);
        assertThat(records).as("执行历史应可分页查询（双写历史零破坏）")
                .hasSizeGreaterThanOrEqualTo(2);
        for (JsonNode record : records) {
            assertThat(textOf(record, "jobKey", "job_key")).isEqualTo(JOB_KEY);
            assertThat(textOf(record, "triggerType", "trigger_type"))
                    .as("triggerType 应归一枚举在场（契约确认⑦）").isNotBlank();
        }
    }

    // ---------- 支撑（照抄范本） ----------

    /**
     * 种子 sys_file 行（原生 SQL 绕开过滤面）。updated_at 表达式以 SQL 片段注入
     * （仅类内常量，无外部输入）；审计列按 AuditBase 惯例。
     */
    private void seedSysFile(long id, String objectKey, String originalName, boolean deleted,
                             String updatedAtExpression) {
        jdbcTemplate.update("""
                        insert into sys_file (id, bucket, object_key, original_name, size, content_type,
                                              biz_type, biz_id, deleted, tenant_id, version,
                                              created_by, created_at, updated_by, updated_at)
                        values (?, 'acme', ?, ?, 5, 'text/plain', 'req-job2', ?, ?, 't-1', 0,
                                'req-job2', now(), 'req-job2', %s)
                        """.formatted(updatedAtExpression),
                id, objectKey, originalName, "b-" + id, deleted);
    }

    private void writeObject(String objectKey, String content) throws IOException {
        Path path = STORAGE_ROOT.resolve(objectKey);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private boolean objectExists(String objectKey) {
        return Files.exists(STORAGE_ROOT.resolve(objectKey));
    }

    private JsonNode triggerRun() {
        try {
            return bodyOf(call(post("/admin/v1/infra/jobs/{jobKey}/run", JOB_KEY)));
        } catch (Exception ex) {
            throw new IllegalStateException("手动触发请求失败", ex);
        }
    }

    /** 触发 + JOB_ALREADY_RUNNING 短窗重试（类内跨用例锁释放竞态容忍；范本口径）。 */
    private JsonNode triggerRunWithRetry() throws Exception {
        long deadline = System.nanoTime() + TRIGGER_SETTLE.toNanos();
        JsonNode body = triggerRun();
        while ("JOB_ALREADY_RUNNING".equals(body.path("code").asText(null))
                && System.nanoTime() < deadline) {
            Thread.sleep(200L);
            body = triggerRun();
        }
        return body;
    }

    private int executionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_job_execution where job_key='" + JOB_KEY + "'", Integer.class);
        return count == null ? 0 : count;
    }

    private long sysFileCount(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_file where id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestExecution() {
        return jdbcTemplate.queryForMap(
                "select job_key, status, started_at, finished_at, error, trigger_type from sys_job_execution "
                        + "where job_key='" + JOB_KEY + "' order by started_at desc limit 1");
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("42", "t-1", Channel.B, Set.of("admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 分页 data 形状宽容解析：data.records 优先，退化 data 数组。 */
    private List<JsonNode> recordList(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode items = data.has("records") ? data.path("records") : data;
        List<JsonNode> list = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(list::add);
        }
        return list;
    }

    /** 多字段名宽容取值（范本 textOf 口径）。 */
    private static String textOf(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText("");
            }
        }
        return "";
    }

    private boolean awaitTrue(Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException pollFailure) {
                // 条件查询前置状态未就绪，继续轮询
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private void clearStorageRoot() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(STORAGE_ROOT))
                    .forEach(path -> path.toFile().delete());
        }
    }
}
