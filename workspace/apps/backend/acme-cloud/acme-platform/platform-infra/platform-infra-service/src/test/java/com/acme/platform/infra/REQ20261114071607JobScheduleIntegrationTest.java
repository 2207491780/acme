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
 * REQ-20261114-071607 任务调度服务域集成测试（Testcontainers PG + Redis，照抄
 * {@code InfraIntegrationTest} 装配口径；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-12 注册表含 file-retention-cleanup、AC-13 手动触发落执行记录 + 任务实际执行、
 * AC-14 ShedLock 并发互斥 JOB_ALREADY_RUNNING、AC-15 软删超期物理清理（未超期不受影响）。
 * 边界补充：未知 jobKey → JOB_NOT_FOUND。</p>
 *
 * <p>口径说明（契约歧义就地标注，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>手动触发的同步性契约未指明（返回 id 时执行是否已完成）：用例以轮询断言
 *       （默认 10s 上限），同步/异步实现均兼容。</li>
 *   <li>sys_job_execution 的 status/trigger 取值枚举契约未指明：断言非空即可，
 *       不钉死枚举字面量。</li>
 *   <li>"软删超期"起算列契约未指明（updated_at 审计列 vs 独立 deleted_at 列）：
 *       用例按审计基类口径回拨 updated_at，若实现采用独立列请同步种子 SQL。</li>
 *   <li>ShedLock 锁行经标准列名（name/lock_until/locked_at/locked_by）预置持锁模拟并发，
 *       列名以 V2 迁移实际 DDL 为准；锁 key 经框架 {@link JobLockKeys} bean 计算，不自造格式。</li>
 *   <li>AC-15 物理清理断言经 JdbcTemplate 原生计数（绕开租户/软删过滤面）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-job-it-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme",
        "acme.storage.upload.retention-days=1"
})
class REQ20261114071607JobScheduleIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String JOB_KEY = "file-retention-cleanup";
    private static final Duration TRIGGER_SETTLE = Duration.ofSeconds(10);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器（委派单 PG+Redis 口径；stream 关闭时当前装配不消费，属性预留注入）。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req20261114071607-job-it");
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
        // 共享环境脏数据 teardown：物理清表（含 ShedLock 锁行）+ 清空本地盘对象
        jdbcTemplate.update("delete from sys_file");
        jdbcTemplate.update("delete from sys_job_execution");
        jdbcTemplate.update("delete from shedlock_locks");
        clearStorageRoot();
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-12：注册表含 file-retention-cleanup（key/description/cron/enabled） ----------

    @Test
    void should_listFileRetentionCleanupWithFullDescriptor_when_queryJobRegistry() throws Exception {
        JsonNode body = bodyOf(call(get("/admin/v1/infra/jobs")));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.isArray()).isTrue();
        JsonNode target = null;
        for (JsonNode job : data) {
            if (JOB_KEY.equals(job.path("key").asText())) {
                target = job;
            }
        }
        assertThat(target).as("注册表应含 %s", JOB_KEY).isNotNull();
        assertThat(target.path("description").asText()).isNotBlank();
        assertThat(target.path("cron").asText()).as("cron 缺省每日 03:00（表达式由实现定）").isNotBlank();
        assertThat(target.hasNonNull("enabled")).as("enabled 字段应在注册表中返回").isTrue();
    }

    // ---------- AC-13：任务空闲手动触发 → 执行记录 id + sys_job_execution + 实际执行 ----------

    @Test
    void should_returnExecutionIdRecordExecutionAndCleanupExpiredFile_when_triggerWhileIdle() throws Exception {
        seedSysFile(910001L, "req-it/2026/01/01/expired-a.txt", "expired-a.txt", true,
                "now() - interval '2 days'");
        writeObject("req-it/2026/01/01/expired-a.txt", "expired-a-content");

        JsonNode body = bodyOf(call(post("/admin/v1/infra/jobs/{jobKey}/run", JOB_KEY)));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").isNull()).as("手动触发应返回执行记录 id").isFalse();
        assertThat(body.path("data").asText()).isNotBlank();

        // sys_job_execution 落 status/started/finished/error（轮询兼容同步/异步落库）
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> executionCount() > 0))
                .as("sys_job_execution 应落执行记录").isTrue();
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> latestExecution().get("finished_at") != null))
                .as("执行完成后应回填 finished_at").isTrue();
        Map<String, Object> done = latestExecution();
        assertThat(String.valueOf(done.get("job_key"))).isEqualTo(JOB_KEY);
        assertThat(String.valueOf(done.get("status"))).isNotBlank();
        assertThat(done.get("started_at")).isNotNull();
        assertThat(done.get("error")).as("成功执行 error 应为空").isNull();

        // 任务实际执行：软删超期文件被清理（记录 + 对象）
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> sysFileCount(910001L) == 0))
                .as("软删超期文件记录应被清理").isTrue();
        assertThat(objectExists("req-it/2026/01/01/expired-a.txt")).isFalse();
    }

    // ---------- AC-14：任务执行中（锁被持）并发触发 → JOB_ALREADY_RUNNING ----------

    @Test
    void should_rejectConcurrentTriggerWithJobAlreadyRunning_when_shedLockHeld() {
        // 经框架 JobLockKeys 计算真实锁 key（服务前缀 + 任务标识，GLOBAL 互斥无租户段），
        // 预置未过期锁行模拟"任务执行中"
        String lockName = jobLockKeys.build(JOB_KEY, null);
        jdbcTemplate.update(
                "insert into shedlock_locks (name, lock_until, locked_at, locked_by) "
                        + "values (?, now() + interval '10 minutes', now(), 'req20261114071607-it')",
                lockName);

        JsonNode body = triggerRun();

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("JOB_ALREADY_RUNNING");
    }

    // ---------- AC-15：软删超期物理清理（记录 + 对象），未超期不受影响 ----------

    @Test
    void should_physicallyRemoveExpiredSoftDeletedAndKeepOthers_when_retentionCleanupRuns() throws Exception {
        seedSysFile(920001L, "req-it/2026/01/02/expired.txt", "expired.txt", true,
                "now() - interval '2 days'");
        seedSysFile(920002L, "req-it/2026/01/02/fresh.txt", "fresh.txt", true, "now()");
        seedSysFile(920003L, "req-it/2026/01/02/active-old.txt", "active-old.txt", false,
                "now() - interval '2 days'");
        writeObject("req-it/2026/01/02/expired.txt", "expired-content");
        writeObject("req-it/2026/01/02/fresh.txt", "fresh-content");
        writeObject("req-it/2026/01/02/active-old.txt", "active-old-content");

        JsonNode body = triggerRun();

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> latestExecution().get("finished_at") != null))
                .as("清理任务应执行完成").isTrue();

        // 超期软删：记录物理清理 + 对象被 remove
        assertThat(awaitTrue(TRIGGER_SETTLE, () -> sysFileCount(920001L) == 0))
                .as("软删超期记录应被物理清理").isTrue();
        assertThat(objectExists("req-it/2026/01/02/expired.txt")).isFalse();

        // 未超期软删：不受影响
        assertThat(sysFileCount(920002L)).isEqualTo(1);
        assertThat(objectExists("req-it/2026/01/02/fresh.txt")).isTrue();

        // 未软删（仅 updatedAt 老）：不受影响
        assertThat(sysFileCount(920003L)).isEqualTo(1);
        assertThat(objectExists("req-it/2026/01/02/active-old.txt")).isTrue();
    }

    // ---------- 边界补充：未知 jobKey → JOB_NOT_FOUND ----------

    @Test
    void should_returnJobNotFound_when_triggerUnknownJobKey() throws Exception {
        JsonNode body = bodyOf(call(post("/admin/v1/infra/jobs/{jobKey}/run", "no-such-job")));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("JOB_NOT_FOUND");
    }

    // ---------- 支撑 ----------

    /**
     * 种子 sys_file 行（原生 SQL 绕开过滤面）。updated_at 表达式以 SQL 片段注入
     * （仅类内常量，无外部输入）；审计列按 AuditBase 惯例（created_by/created_at/updated_by/
     * updated_at + version），以 V2 迁移实际列为准。
     */
    private void seedSysFile(long id, String objectKey, String originalName, boolean deleted,
                             String updatedAtExpression) {
        jdbcTemplate.update("""
                        insert into sys_file (id, bucket, object_key, original_name, size, content_type,
                                              biz_type, biz_id, deleted, tenant_id, version,
                                              created_by, created_at, updated_by, updated_at)
                        values (?, 'acme', ?, ?, 5, 'text/plain', 'req-it', ?, ?, 't-1', 0,
                                'req-it', now(), 'req-it', %s)
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

    private int executionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_job_execution where job_key='" + JOB_KEY + "'", Integer.class);
        return count == null ? 0 : count;
    }

    private long sysFileCount(long id) {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_file where id=?", Long.class, id);
        return count == null ? 0L : count;
    }

    private Map<String, Object> latestExecution() {
        return jdbcTemplate.queryForMap(
                "select job_key, status, started_at, finished_at, error from sys_job_execution "
                        + "where job_key='" + JOB_KEY + "' order by started_at desc limit 1");
    }

    /** 轮询至条件成立或超时（手动触发同步性未指明，兼容异步落库）。 */
    private static boolean awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException pollFailure) {
                // 条件查询前置状态未就绪（如执行记录尚未落库），继续轮询
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
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
