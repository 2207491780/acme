package com.acme.platform.scheduler;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-200724（JOB-2）调度中心侧集成测试——V2 种子迁移 + 失联 OFFLINE + 下发执行记录
 * （Testcontainers PG 自起容器；装配照抄 {@code REQ20261121071002SchedulerCoreIntegrationTest}
 * 先例：MockMvc HTTP 面 + JdbcTemplate 夹具 + 内嵌桩执行器（JDK HttpServer）；
 * 无 Docker 自动跳过——判定口径 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1（OFFLINE 腿）/AC-7/AC-9：</p>
 * <ul>
 *   <li>AC-1：心跳中断超 stale（30s）→ 调度中心侧 OFFLINE；心跳恢复 → ONLINE。
 *       （SDK 注册/心跳发起面在 framework-job REQ20261121200724ExecutorSdkIntegrationTest。）</li>
 *   <li>AC-7：V2 迁移 4 行幂等种子（jobKey 沿用现 taskId 值，appName=platform-infra，
 *       cron=现 acme.*.retention-cron 默认值）；迁移重放不重复、不覆盖运行态。</li>
 *   <li>AC-9：调度中心下发执行→sch_job_execution 记录完整（jobKey/state/trigger_type=SCHEDULE
 *       /attempt/cost_ms/fire_time/trace_id）。</li>
 * </ul>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 种子 cron 取 infra application.yml 缺省值：file=0 0 3 * * *、login-log=0 30 3 * * *、
 *     exception-log=0 40 3 * * *、audit=0 40 4 * * *（契约确认⑦「cron 取自现默认值」）；
 * A-2 种子初始 status 不钉死（契约未指明 ENABLED/DISABLED），只断言种子在位与字段完整；
 *     幂等不覆盖运行态用「夹具改 status 后重放仍保留」验证；
 * A-3 V2 文件定位=模块 resources db/migration/scheduler 下 V2__*.sql（文件名无关 glob），
 *     重放经 ScriptUtils 原样二次执行。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSchedulerApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-scheduler-it-secret-0123456789"
})
class REQ20261121200724SchedulerV2IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String STUB_APP = "acme-job2-executor-stub";

    private static final String JOBS_URL = "/admin/v1/scheduler/jobs";
    private static final String EXECUTORS_URL = "/admin/v1/scheduler/executors";
    /** 注册端点：JOB-1 已合流实现 /register（范本口径）；JOB-2 契约 apis 列 /executors——偏差上报 TL 裁决。 */
    private static final String REGISTER_URL = "/internal/v1/scheduler/executors/register";
    private static final String HEARTBEAT_URL = "/internal/v1/scheduler/executors/heartbeat";

    private static final Pattern RUN_PATH =
            Pattern.compile("/internal/v1/job/executions/([^/]+)/run");

    private static final String ACCEPTED_BODY = "{\"accepted\":true}";

    /** AC-7 种子 jobKey（契约确认⑦：jobKey 沿用现 taskId 值）。 */
    private static final List<String> SEED_JOB_KEYS = List.of(
            "file-retention-cleanup",
            "login-log-retention-cleanup",
            "exception-log-retention-cleanup",
            "audit-retention-cleanup");

    /** AC-7 种子 cron（A-1：infra retention-cron 缺省值）。 */
    private static final Map<String, String> SEED_CRONS = Map.of(
            "file-retention-cleanup", "0 0 3 * * *",
            "login-log-retention-cleanup", "0 30 3 * * *",
            "exception-log-retention-cleanup", "0 40 3 * * *",
            "audit-retention-cleanup", "0 40 4 * * *");

    /** 本类用例 jobKey 前缀（teardown 只清自有数据，种子保留）。 */
    private static final String IT_PREFIX = "it200724";

    /** 内嵌执行器桩（AC-9 下发链路；JDK HttpServer 照 JOB-1 范本）。 */
    private static final ExecutorStub STUB = ExecutorStub.start();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        // lb://{app_name} 指向内嵌桩（范本 A-2 同款）
        registry.add("spring.cloud.discovery.client.simple.instances." + STUB_APP + "[0].uri",
                () -> STUB.baseUri());
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthorizationClient authorizationClient;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        STUB.reset();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
    }

    @AfterEach
    void cleanUp() {
        // teardown：只清本类前缀用例数据，AC-7 种子行保留（admin DELETE 卸载内核轮条目后裸 SQL 清）；
        // execution/registry 无内核耦合，SQL 物理清；锁行复位写 UTC 墙钟（范本 round-2 口径）
        STUB.reset();
        try {
            for (Long jobId : jdbcTemplate.queryForList(
                    "select id from sch_job_definition where job_key like ?", Long.class,
                    IT_PREFIX + "%")) {
                try {
                    callAsSuperAdmin(delete(JOBS_URL + "/" + jobId));
                } catch (Exception cleanupFailure) {
                    // 尽力清理，单个失败不阻断其余清理
                }
            }
        } catch (Exception ignored) {
            // 夹具读取失败（上下文已死等）不阻断
        }
        jdbcTemplate.update("delete from sch_job_execution where job_key like ?", IT_PREFIX + "%");
        jdbcTemplate.update("delete from sch_job_definition where job_key like ?", IT_PREFIX + "%");
        jdbcTemplate.update("delete from sch_executor_registry where app_name = ?", STUB_APP);
        jdbcTemplate.update(
                "update sch_scheduler_lock set lock_until = timezone('utc', now()) - interval '1 seconds'");
    }

    // ---------- AC-7：V2 幂等种子（迁移 + 重放不重复不覆盖运行态） ----------

    @Test
    void should_seed_four_infra_job_definitions_idempotently_when_v2_migration_replayed()
            throws Exception {
        // ① Flyway V2 迁移执行成功
        Long v2Rows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success", Long.class);
        assertThat(v2Rows).as("scheduler V2 迁移应已执行成功").isGreaterThan(0L);

        // ② 4 行种子在位且字段完整（jobKey/appName/handler/trigger/cron——契约确认⑦+A-1）
        for (String jobKey : SEED_JOB_KEYS) {
            Map<String, Object> row = seedRow(jobKey);
            assertThat(row).as("种子 %s 应在 sch_job_definition 在位", jobKey).isNotNull();
            assertThat(row.get("app_name")).as("种子 %s appName=platform-infra", jobKey)
                    .isEqualTo("platform-infra");
            assertThat(row.get("handler")).as("种子 %s handler=jobKey", jobKey).isEqualTo(jobKey);
            assertThat(row.get("trigger_type")).as("种子 %s 应为 CRON 触发", jobKey).isEqualTo("CRON");
            assertThat(row.get("cron_expr")).as("种子 %s cron 应取 infra 缺省值（A-1）", jobKey)
                    .isEqualTo(SEED_CRONS.get(jobKey));
            assertThat(row.get("misfire_policy")).as("种子 %s misfire_policy 应非空", jobKey).isNotNull();
            assertThat(row.get("timeout_ms")).as("种子 %s timeout_ms 应非空", jobKey).isNotNull();
        }

        // ③ 运行态模拟：修改 file-retention-cleanup 运行态（status/version/next_fire_time）
        jdbcTemplate.update(
                "update sch_job_definition set status = 'ENABLED', version = version + 1, "
                        + "next_fire_time = now() + interval '1 hours' where job_key = ?",
                "file-retention-cleanup");

        // ④ 迁移重放（A-3：V2__*.sql 原样二次执行）
        Path v2File = v2MigrationFile();
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(v2File.toFile()));
        }

        // ⑤ 幂等：重放后仍恰 4 行（不重复）；运行态不被覆盖
        Long seedCount = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key in (?,?,?,?)",
                Long.class, SEED_JOB_KEYS.get(0), SEED_JOB_KEYS.get(1),
                SEED_JOB_KEYS.get(2), SEED_JOB_KEYS.get(3));
        assertThat(seedCount).as("重放后种子应仍恰 4 行（uk 幂等不重复）").isEqualTo(4L);
        Map<String, Object> after = seedRow("file-retention-cleanup");
        assertThat(after.get("status")).as("重放不得覆盖运行态 status").isEqualTo("ENABLED");
        assertThat(((Number) after.get("version")).longValue())
                .as("重放不得回滚运行态 version").isGreaterThanOrEqualTo(1L);
        assertThat(after.get("next_fire_time")).as("重放不得清空运行态 next_fire_time").isNotNull();
    }

    // ---------- AC-1（OFFLINE 腿）：心跳 stale>30s → OFFLINE；恢复心跳 → ONLINE ----------

    @Test
    void should_mark_executor_offline_when_heartbeat_stale_beyond_30s() throws Exception {
        String instanceId = uniq("inst-off");

        // 注册入库（ONLINE）
        registerExecutor(instanceId);
        Map<String, Object> row = registryRow(STUB_APP, instanceId);
        assertThat(row).as("注册应落 sch_executor_registry").isNotNull();
        assertThat(row.get("status")).as("注册态应 ONLINE").isEqualTo("ONLINE");

        // 夹具造 stale 31s（> 失联阈值 30s）→ OFFLINE
        jdbcTemplate.update(
                "update sch_executor_registry set heartbeat_at = now() - interval '31 seconds' "
                        + "where app_name = ? and instance_id = ?", STUB_APP, instanceId);
        boolean offline = await(12_000, () -> {
            try {
                return "OFFLINE".equals(executorListStatus(STUB_APP, instanceId));
            } catch (Exception pollFailure) {
                return false;
            }
        });
        assertThat(offline)
                .as("心跳 stale>30s 应 OFFLINE，实际=%s", executorListStatus(STUB_APP, instanceId))
                .isTrue();

        // 心跳恢复 → ONLINE（心跳按注册语义 upsert）
        ObjectNode heartbeat = JSON.createObjectNode();
        heartbeat.put("appName", STUB_APP);
        heartbeat.put("instanceId", instanceId);
        ok(internalCall(post(HEARTBEAT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(heartbeat))), "恢复心跳应成功");
        boolean online = await(8_000, () -> {
            try {
                return "ONLINE".equals(executorListStatus(STUB_APP, instanceId));
            } catch (Exception pollFailure) {
                return false;
            }
        });
        assertThat(online).as("恢复心跳后应回 ONLINE").isTrue();
    }

    // ---------- AC-9：调度下发执行 → sch_job_execution 记录完整 ----------

    @Test
    void should_record_complete_execution_when_scheduler_dispatched_execution_completes()
            throws Exception {
        registerExecutor(uniq("inst-rec"));
        String jobKey = uniq("jobrec");
        long jobId = createFixedRateJob(jobKey, 1_000L);
        enableJob(jobId);

        // 下发 → 接单 → RUNNING → 回调 SUCCESS（携带 costMs）
        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应向执行器下发 POST /internal/v1/job/executions/{id}/run").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        pushToRunning(executionId);
        ok(reportResult(executionId, "SUCCESS", null, 1_500L), "回调 SUCCESS 应成功");
        assertThat(await(6_000, () -> "SUCCESS".equals(stateOf(executionId))))
                .as("回调后应 SUCCESS，实际=%s", stateOf(executionId)).isTrue();

        // sch_job_execution 记录完整（jobKey/state/trigger=调度下发/attempt/cost_ms/fire_time/trace）
        Map<String, Object> executionRow = executionRow(executionId);
        assertThat(executionRow.get("job_key")).as("执行记录 job_key").isEqualTo(jobKey);
        assertThat(executionRow.get("state")).as("执行记录 state").isEqualTo("SUCCESS");
        assertThat(executionRow.get("trigger_type")).as("调度下发的 trigger_type 应为 SCHEDULE")
                .isEqualTo("SCHEDULE");
        assertThat(((Number) executionRow.get("attempt")).intValue())
                .as("首发 attempt=0").isZero();
        assertThat(((Number) executionRow.get("cost_ms")).longValue())
                .as("cost_ms 应取上报耗时").isEqualTo(1_500L);
        assertThat(executionRow.get("fire_time")).as("fire_time 应在记录").isNotNull();
        assertThat(executionRow.get("trace_id")).as("trace_id 应在记录").isNotNull();
        assertThat(executionRow.get("error")).as("成功执行 error 应为空").isNull();
    }

    // ---------- 夹具装配（照抄范本） ----------

    private String uniq(String prefix) {
        return IT_PREFIX + SEQ.incrementAndGet() + prefix;
    }

    /** FIXED_RATE 任务请求体（retry/timeout 契约缺省形态）。 */
    private ObjectNode fixedRateBody(String jobKey, long fixedRateMs) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jobKey", jobKey);
        node.put("appName", STUB_APP);
        node.put("handler", jobKey + ".handler");
        node.put("timeoutMs", 60_000L);
        node.put("misfirePolicy", "FIRE_ONCE_NOW");
        ObjectNode retry = node.putObject("retryPolicy");
        retry.put("maxAttempts", 1);
        retry.put("initialIntervalMs", 100);
        node.put("triggerType", "FIXED_RATE");
        node.put("fixedRateMs", fixedRateMs);
        return node;
    }

    private long createFixedRateJob(String jobKey, long fixedRateMs) throws Exception {
        JsonNode resp = callAsSuperAdmin(post(JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(fixedRateBody(jobKey, fixedRateMs))));
        ok(resp, "创建任务应成功");
        return jobIdOf(jobKey);
    }

    private void enableJob(long jobId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", "ENABLED");
        body.put("version", jobVersion(jobId));
        ok(callAsSuperAdmin(put(JOBS_URL + "/" + jobId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))), "启用任务应成功");
    }

    /** 执行器注册入桩应用（appName/instanceId/host/port/handlers）。 */
    private void registerExecutor(String instanceId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("appName", STUB_APP);
        body.put("instanceId", instanceId);
        body.put("host", "127.0.0.1");
        body.put("port", STUB.port());
        var handlers = body.putArray("handlers");
        handlers.add("it200724.handler");
        ok(internalCall(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))), "执行器注册应成功");
    }

    // ---------- HTTP 支撑（照抄范本） ----------

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject(userId, tenant, Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAs("1", "default", Set.of("super_admin"), request);
        assertThat(result.getResponse().getStatus())
                .as("admin 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private JsonNode internalCall(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "platform-scheduler-it", null, Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "it-stub", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** /result 回调（扩展 costMs：AC-9 记录完整性口径）。 */
    private JsonNode reportResult(long executionId, String state, String error, Long costMs)
            throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("state", state);
        if (error != null) {
            body.put("error", error);
        }
        if (costMs != null) {
            body.put("costMs", costMs);
        }
        return internalCall(post("/internal/v1/scheduler/executions/" + executionId + "/result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private List<JsonNode> dataItems(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode items = data.has("records") ? data.path("records") : data;
        List<JsonNode> list = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(list::add);
        }
        return list;
    }

    // ---------- DB 夹具读取（库中真实状态推演，pitfalls ⑥） ----------

    private long jobIdOf(String jobKey) {
        Long id = jdbcTemplate.queryForObject(
                "select id from sch_job_definition where job_key = ?", Long.class, jobKey);
        assertThat(id).as("任务 %s 应已落库", jobKey).isNotNull();
        return id;
    }

    private int jobVersion(long jobId) {
        return jdbcTemplate.queryForObject(
                "select version from sch_job_definition where id = ?", Integer.class, jobId);
    }

    private Map<String, Object> seedRow(String jobKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select app_name, handler, trigger_type, cron_expr, misfire_policy, timeout_ms, "
                        + "status, version, next_fire_time from sch_job_definition where job_key = ?",
                jobKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> executionRow(long executionId) {
        return jdbcTemplate.queryForMap(
                "select job_key, state, trigger_type, attempt, cost_ms, fire_time, trace_id, error "
                        + "from sch_job_execution where id = ?", executionId);
    }

    private String stateOf(long executionId) {
        List<String> states = jdbcTemplate.queryForList(
                "select state from sch_job_execution where id = ?", String.class, executionId);
        return states.isEmpty() ? null : states.get(0);
    }

    private Map<String, Object> registryRow(String appName, String instanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status, heartbeat_at from sch_executor_registry "
                        + "where app_name = ? and instance_id = ?", appName, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- 轮询支撑（照抄范本口径） ----------

    private static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(100);
        }
        return condition.getAsBoolean();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private RecordedRequest awaitDispatch(String jobKey, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RecordedRequest request : STUB.requests()) {
                if (request.path().startsWith("/internal/v1/job/executions/")
                        && request.body().contains(jobKey)) {
                    return request;
                }
            }
            sleepQuietly(100);
        }
        return null;
    }

    /** 把执行推进到 RUNNING（范本 A-5 宽容分支：DISPATCHED 态补发 RUNNING 回调）。 */
    private void pushToRunning(long executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 6_000;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = stateOf(executionId);
            if ("DISPATCHED".equals(state) || "RUNNING".equals(state)) {
                break;
            }
            sleepQuietly(100);
        }
        if ("DISPATCHED".equals(state)) {
            ok(reportResult(executionId, "RUNNING", null, null), "回调 RUNNING 应成功");
        }
        boolean running = await(6_000, () -> "RUNNING".equals(stateOf(executionId)));
        assertThat(running).as("执行应进入 RUNNING，实际=%s", stateOf(executionId)).isTrue();
    }

    private long executionIdOf(String dispatchPath) {
        Matcher matcher = RUN_PATH.matcher(dispatchPath);
        assertThat(matcher.matches())
                .as("下发路径应照协议 /internal/v1/job/executions/{executionId}/run：%s", dispatchPath)
                .isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private String executorListStatus(String appName, String instanceId) throws Exception {
        JsonNode body = ok(callAsSuperAdmin(get(EXECUTORS_URL)), "executors 列表应成功");
        for (JsonNode item : dataItems(body)) {
            if (appName.equals(item.path("appName").asText())
                    && instanceId.equals(item.path("instanceId").asText())) {
                return item.path("status").asText(null);
            }
        }
        return null;
    }

    // ---------- V2 迁移文件定位（A-3：文件名无关 glob） ----------

    private static Path v2MigrationFile() throws IOException {
        Path dir = acmeCloudRoot().resolve(
                "acme-platform/platform-scheduler/platform-scheduler-service/src/main/resources/db/migration/scheduler");
        assertThat(Files.isDirectory(dir)).as("scheduler 迁移目录应存在：%s", dir).isTrue();
        try (var files = Files.list(dir)) {
            Optional<Path> v2 = files
                    .filter(p -> p.getFileName().toString().startsWith("V2__"))
                    .findFirst();
            assertThat(v2).as("scheduler V2 迁移文件应已交付（未交付则本用例红=实现未交付）").isPresent();
            return v2.get();
        }
    }

    /** 从 user.dir 向上定位 acme-cloud 根（范本同款）。 */
    private static Path acmeCloudRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("acme-gateway/src/main/resources/application.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "无法定位 acme-cloud 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }

    // ---------- 内嵌执行器桩（JDK HttpServer，照 JOB-1 范本精简） ----------

    record RecordedRequest(String method, String path, String body, Instant receivedAt) {
    }

    static final class ExecutorStub {

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private ExecutorStub(HttpServer server) {
            this.server = server;
        }

        static ExecutorStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorStub stub = new ExecutorStub(server);
                server.createContext("/", stub::handle);
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌执行器桩启动失败", ex);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUri() {
            return "http://127.0.0.1:" + port();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void reset() {
            requests.clear();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            RecordedRequest recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    Instant.now());
            requests.add(recorded);
            byte[] bytes = ACCEPTED_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
