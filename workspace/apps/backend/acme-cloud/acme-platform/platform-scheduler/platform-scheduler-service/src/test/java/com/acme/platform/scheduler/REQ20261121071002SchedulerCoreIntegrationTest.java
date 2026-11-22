package com.acme.platform.scheduler;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.scheduler.api.SchedulerErrorCodes;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-071002 调度中心核心——服务级集成测试（Testcontainers PG 自起容器；
 * platform-scheduler-service 无 Kafka 依赖，不需 Kafka 容器；装配口径照
 * {@code REQ20261121054613CapabilityApiIntegrationTest}/{@code SystemIntegrationTest} 惯例：
 * MockMvc HTTP 面 + JdbcTemplate 夹具；无 Docker 自动跳过——判定口径：日志须见
 * Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1~2、AC-5~6、AC-8~14（内核时序语义 AC-3/AC-4/AC-7 在
 * {@code REQ20261121071002SchedulerKernelTest}；AC-1 的 V26 权限种子面在 platform-system
 * 侧由 {@code SystemIntegrationTest} 基线断言适配承载，映射见交付报告）：</p>
 * <ul>
 *   <li>AC-1：V1 四表就绪 + 部分唯一索引 uk(def_id,fire_time) WHERE trigger_type='SCHEDULE'
 *       AND attempt=0 在场 + Flyway V1 执行成功（V26 权限面=SystemIntegrationTest 适配）。</li>
 *   <li>AC-2：创建默认 DISABLED；/status 专用端点显式启用；重复 jobKey→业务拒绝（DUPLICATE）；
 *       CRON 语法错→业务拒绝（SCHEDULER_TRIGGER_INVALID）且不落库；job_key 不可改
 *       （PUT 变更被拒=SCHEDULER_JOB_KEY_IMMUTABLE），合法更新（不含 jobKey）成功。</li>
 *   <li>AC-3（HTTP 面）：executions 分页 jobKey 过滤；/trigger 生成 MANUAL execution
 *       （权限 job:update，手动触发语义）。</li>
 *   <li>AC-5：内嵌 HTTP 桩（JDK HttpServer 照 /internal/v1/job/executions/{id}/run 协议接单）：
 *       PENDING→DISPATCHED（下发 body 字段断言）→回调 RUNNING→回调 SUCCESS 全链路；
 *       非法跃迁拒绝（PENDING/DISPATCHED 直接 SUCCESS、终态后再回调，确认④ CAS 口径）。</li>
 *   <li>AC-6：接单超时 10s→FAILED(DISPATCH_TIMEOUT)→RETRY attempt+1；无在线执行器→
 *       FAILED(NO_EXECUTOR)→RETRY；回调 FAILED→RETRY；maxAttempts 耗尽→终态 FAILED。</li>
 *   <li>AC-8：RUNNING 超 timeout_ms→巡检标 TIMEOUT→走重试策略（RETRY attempt=1）。</li>
 *   <li>AC-9：同 job 串行——上一 execution 未终态（下发挂起）不触发下一次 SCHEDULE。</li>
 *   <li>AC-10：执行器注册入库（uk upsert）/心跳刷新 heartbeat_at/心跳 stale>30s→OFFLINE
 *       （heartbeat_at 经 JdbcTemplate UPDATE 夹具造旧值）。</li>
 *   <li>AC-11：无权限租户令牌访问 admin 面→403（既有 RBAC 口径，PERMISSION_DENIED 翻译）。</li>
 *   <li>AC-12：POST /internal/v1/scheduler/jobs 一次性任务注册（BPM 开通位）→ONCE 定义
 *       落库并在 onceFireAt 触发下发到桩。</li>
 *   <li>AC-13：网关路由/聚合文档分组源级断言（application.yml 含 /admin/v1/scheduler/** 路由、
 *       lb://platform-scheduler、/v3/api-docs/scheduler 分组；/internal 不在路由）。</li>
 *   <li>AC-14：bootstrap 聚合轻量断言（acme-bootstrap pom 含 platform-scheduler-service +
 *       单体扫描面 com.acme.platform + 服务主类文件在场——不做全上下文启动，手段如实上报）。</li>
 * </ul>
 *
 * <p>业务失败一律 Result 信封 code≠0（错误码语义名 DUPLICATE / SCHEDULER_TRIGGER_INVALID /
 * SCHEDULER_JOB_KEY_IMMUTABLE 按契约钉死，wire 三段式码不猜字面量、统一断言 code≠0）；
 * internal 面照 REQ-054613 先例：InternalTokenAuthFilter 显式装配 + X-Acme-Token 内部令牌。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 主应用类=com.acme.platform.scheduler.PlatformSchedulerApplication（契约钉死双模块与
 *     「惯例照 platform-system」，类名按惯例推导）；
 * A-2 下发通道=Spring Cloud LoadBalancer 解析 lb://{app_name}（spec §4），测试以
 *     spring.cloud.discovery.client.simple.instances 静态实例表把桩应用指到内嵌 HTTP 桩
 *     （Nacos 关闭态下 lb:// 唯一可达解析面）；
 * A-3 管理/内部端点请求体与响应 data 形状按 V1 列名驼峰推导（jobKey/appName/handler/
 *     triggerType/cronExpr/fixedRateMs/onceFireAt/retryPolicy{maxAttempts,initialIntervalMs}/
 *     timeoutMs/misfirePolicy/status；分页 data.records），失败即上报；
 * A-4 execution 主键数值型，回调 URL executionId 由下发路径反解；
 * A-5 状态机按 TL 钉死链 PENDING→DISPATCHED→（回调）RUNNING→SUCCESS 断言；「接单 200 后
 *     直接 RUNNING」实现形态经宽容分支兼容（DISPATCHED 才补发 RUNNING 回调），两种形态
 *     均验全链路，差异如实上报。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSchedulerApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-scheduler-it-secret-0123456789"
})
class REQ20261121071002SchedulerCoreIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 内嵌执行器桩注册进发现层的应用名（A-2）。 */
    private static final String STUB_APP = "acme-executor-stub";

    /** 无执行器应用名（AC-6 NO_EXECUTOR 腿；不在发现层实例表）。 */
    private static final String GHOST_APP = "acme-ghost-app-no-executor";

    private static final String JOBS_URL = "/admin/v1/scheduler/jobs";
    private static final String EXECUTIONS_URL = "/admin/v1/scheduler/executions";
    private static final String EXECUTORS_URL = "/admin/v1/scheduler/executors";
    private static final String REGISTER_URL = "/internal/v1/scheduler/executors/register";
    private static final String HEARTBEAT_URL = "/internal/v1/scheduler/executors/heartbeat";
    private static final String INTERNAL_JOBS_URL = "/internal/v1/scheduler/jobs";

    /** 下发协议路径模板（TL 钉死 body 断言面）：POST /internal/v1/job/executions/{id}/run。 */
    private static final Pattern RUN_PATH =
            Pattern.compile("/internal/v1/job/executions/([^/]+)/run");

    private static final String ACCEPTED_BODY = "{\"accepted\":true}";

    /** 内嵌执行器桩（真 HTTP 面：记录 /run 请求、可挂起接单/拒绝接单）。 */
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
        // A-2：Nacos 关闭态以 Spring Cloud SimpleDiscoveryClient 静态实例表承载 lb://{app_name}
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

    /** 跨域权限判定桩（REQ-054613/infra FileServiceIntegrationTest 先例：判定走 platform-system
     *  Feign 契约，单服务测试域以 MockitoBean 桩件承载——缺省放行，AC-11 用例内切拒绝）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    /** ShedLock 供应器（teardown 与内核同口径清缓存，防删行后误判锁被持——infra 先例）。 */
    @Autowired(required = false)
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        STUB.reset(ExecutorStub.Scenario.ACCEPT_OK);
        // 权限判定缺省放行（单服务测试域无 platform-system 实例，fail-closed 会拒绝一切 admin 面）
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据：任务定义走 admin DELETE 契约面（内核同步卸载时间轮条目——
        // 裸 SQL 删 definition 会留悬空轮条目，内核开火已删定义会中断 @Scheduled tick 链，
        // 测试域实证）；execution/registry 行无内核耦合，SQL 物理清（绕 mapper 过滤面）。
        // sch_scheduler_lock 不删行（删行与在途 tick 获取存在竞态），仅复位 lock_until 已过期。
        STUB.reset(ExecutorStub.Scenario.ACCEPT_OK);
        try {
            for (Long jobId : jdbcTemplate.queryForList("select id from sch_job_definition", Long.class)) {
                try {
                    callAsSuperAdmin(delete(JOBS_URL + "/" + jobId));
                } catch (Exception cleanupFailure) {
                    // 尽力清理，单个失败不阻断其余清理
                }
            }
        } catch (Exception ignored) {
            // 夹具读取失败（上下文已死等）不阻断
        }
        jdbcTemplate.update("delete from sch_job_execution");
        jdbcTemplate.update("delete from sch_job_definition");
        jdbcTemplate.update("delete from sch_executor_registry");
        // 锁行复位必须写 UTC 墙钟（timezone('utc', now())，与 ShedLock usingDbTime 同一参照系）：
        // 裸 now() 是绝对时刻，经 JDBC 会话时区（Asia/Shanghai）落在 ShedLock 比较视界 +8h →
        // 假锁约 8 小时、tick 永久停摆（round-2 根因，V1 DDL 注释同口径）。
        jdbcTemplate.update(
                "update sch_scheduler_lock set lock_until = timezone('utc', now()) - interval '1 seconds'");
        clearLockProviderCache();
    }

    /** 删行后须清 ShedLock 内存缓存（infra JobSchedule 先例：否则下次获取跳过 INSERT 直走 UPDATE）。 */
    private void clearLockProviderCache() {
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    // ---------- 夹具装配（契约 V1 列名驼峰请求体，A-3） ----------

    private String uniq(String prefix) {
        return "it071002" + SEQ.incrementAndGet() + prefix;
    }

    /** 任务定义请求体缺省骨架（retry/misfire/timeout 给契约默认形态；创建默认 DISABLED）。 */
    private ObjectNode baseJobBody(String jobKey, String appName, String handler) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jobKey", jobKey);
        node.put("appName", appName);
        node.put("handler", handler);
        node.put("timeoutMs", 60_000L);
        node.put("misfirePolicy", "FIRE_ONCE_NOW");
        ObjectNode retry = node.putObject("retryPolicy");
        retry.put("maxAttempts", 1);
        retry.put("initialIntervalMs", 100);
        return node;
    }

    /** CRON 任务（6 位域，Spring CronExpression）。 */
    private ObjectNode cronBody(String jobKey, String appName, String handler, String cronExpr) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "CRON");
        node.put("cronExpr", cronExpr);
        return node;
    }

    /** FIXED_RATE 任务；maxAttempts 显式随体（重试链用例需要差分）。 */
    private ObjectNode fixedRateBody(String jobKey, String appName, String handler,
                                     long fixedRateMs, int maxAttempts) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "FIXED_RATE");
        node.put("fixedRateMs", fixedRateMs);
        ((ObjectNode) node.get("retryPolicy")).put("maxAttempts", maxAttempts);
        return node;
    }

    /** ONCE 任务：onceFireAt（ISO-8601）+ misfire 策略随体。 */
    private ObjectNode onceBody(String jobKey, String appName, String handler, Instant fireAt,
                                String misfirePolicy) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "ONCE");
        node.put("onceFireAt", fireAt.toString());
        node.put("misfirePolicy", misfirePolicy);
        return node;
    }

    /** 创建任务（admin 面，成功信封断言）；id 经 job_key 唯一列回查 DB（不猜响应 data 形状）。 */
    private long createJobOk(ObjectNode body, String jobKey) throws Exception {
        JsonNode resp = callAsSuperAdmin(post(JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        ok(resp, "创建任务应成功");
        return jobIdOf(jobKey);
    }

    /** /status 专用端点显式启用（契约④：创建默认 DISABLED、显式启用）。 */
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
        handlers.add("it-handler-a");
        ok(internalCall(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))), "执行器注册应成功");
    }

    // ---------- HTTP 支撑 ----------

    private String userToken(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, Channel.B, roles));
    }

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", userToken(userId, tenant, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    /** 平台管理操作者：种子超管（userId=1 / default 租户 / super_admin 短路全量权限点）。 */
    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAs("1", "default", Set.of("super_admin"), request);
        assertThat(result.getResponse().getStatus())
                .as("admin 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    /** internal 面（MockMvc 直调 + 内部令牌，REQ-054613 先例口径）。 */
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

    /** 业务失败信封断言（pitfalls 口径：Result.code≠0，勿断言 4xx）。 */
    private JsonNode bizFail(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + " 应业务失败：%s", body).isFalse();
        assertThat(body.path("code").asText()).as(desc + " code 应≠0：%s", body).isNotEqualTo("0");
        return body;
    }

    /** /result 回调（执行器角色经 MockMvc 直调调度中心回调端点，协议 POST
     * /internal/v1/scheduler/executions/{executionId}/result）。 */
    private JsonNode reportResult(long executionId, String state, String error) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("state", state);
        if (error != null) {
            body.put("error", error);
        }
        return internalCall(post("/internal/v1/scheduler/executions/" + executionId + "/result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 分页/列表 data 形状宽容解析：data.records 优先，退化 data 数组（A-3）。 */
    private List<JsonNode> dataItems(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode items = data.has("records") ? data.path("records") : data;
        List<JsonNode> list = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(list::add);
        }
        return list;
    }

    // ---------- DB 夹具读取支撑（库中真实状态推演，pitfalls ⑥） ----------

    private long jobIdOf(String jobKey) {
        Long id = jdbcTemplate.queryForObject(
                "select id from sch_job_definition where job_key = ?", Long.class, jobKey);
        assertThat(id).as("任务 %s 应已落库", jobKey).isNotNull();
        return id;
    }

    private String definitionStatus(long jobId) {
        return jdbcTemplate.queryForObject(
                "select status from sch_job_definition where id = ?", String.class, jobId);
    }

    private int jobVersion(long jobId) {
        return jdbcTemplate.queryForObject(
                "select version from sch_job_definition where id = ?", Integer.class, jobId);
    }

    private String jobKeyOf(long jobId) {
        return jdbcTemplate.queryForObject(
                "select job_key from sch_job_definition where id = ?", String.class, jobId);
    }

    private String cronExprOf(long jobId) {
        return jdbcTemplate.queryForObject(
                "select cron_expr from sch_job_definition where id = ?", String.class, jobId);
    }

    /** SCHEDULE attempt=0 的 execution 行数（触发幂等/串行断言口径）。 */
    private long scheduleCount(String jobKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Long.class, jobKey);
        return count == null ? 0L : count;
    }

    private boolean hasTriggerType(String jobKey, String triggerType) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? and trigger_type = ?",
                Long.class, jobKey, triggerType);
        return count != null && count > 0;
    }

    private boolean hasRetryAttempt(String jobKey, int attempt) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'RETRY' and attempt = ?", Long.class, jobKey, attempt);
        return count != null && count > 0;
    }

    /** 该 jobKey 全部 execution 行的 attempt 水位（无行=0；耗尽断言口径：不得超出 maxAttempts）。 */
    private long maxAttemptOf(String jobKey) {
        Long maxAttempt = jdbcTemplate.queryForObject(
                "select coalesce(max(attempt), 0) from sch_job_execution where job_key = ?",
                Long.class, jobKey);
        return maxAttempt == null ? 0L : maxAttempt;
    }

    private String stateOf(long executionId) {
        List<String> states = jdbcTemplate.queryForList(
                "select state from sch_job_execution where id = ?", String.class, executionId);
        return states.isEmpty() ? null : states.get(0);
    }

    private String errorOf(long executionId) {
        List<String> errors = jdbcTemplate.queryForList(
                "select error from sch_job_execution where id = ?", String.class, executionId);
        return errors.isEmpty() ? null : errors.get(0);
    }

    private String latestScheduleState(String jobKey) {
        List<String> states = jdbcTemplate.queryForList(
                "select state from sch_job_execution where job_key = ? and trigger_type = 'SCHEDULE' "
                        + "and attempt = 0 order by id desc limit 1", String.class, jobKey);
        return states.isEmpty() ? null : states.get(0);
    }

    private String latestScheduleError(String jobKey) {
        List<String> errors = jdbcTemplate.queryForList(
                "select error from sch_job_execution where job_key = ? and trigger_type = 'SCHEDULE' "
                        + "and attempt = 0 order by id desc limit 1", String.class, jobKey);
        return errors.isEmpty() ? null : errors.get(0);
    }

    private long registryRowCount(String appName, String instanceId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_executor_registry where app_name = ? and instance_id = ?",
                Long.class, appName, instanceId);
        return count == null ? 0L : count;
    }

    private Map<String, Object> registryRow(String appName, String instanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status, heartbeat_at from sch_executor_registry "
                        + "where app_name = ? and instance_id = ?", appName, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- 轮询支撑（pitfalls 15 口径：自旋 + 足够余量，不 sleep 死等） ----------

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

    /** 观察窗：windowMs 内条件持续为真才通过（负例/稳定性断言口径）。 */
    private static boolean staysTrueFor(long windowMs, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < end) {
            if (!condition.getAsBoolean()) {
                return false;
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

    private String awaitExecutionState(long executionId, long timeoutMs, String... expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = stateOf(executionId);
            for (String candidate : expected) {
                if (candidate.equals(state)) {
                    return state;
                }
            }
            sleepQuietly(100);
        }
        return state;
    }

    /** 把执行推进到 RUNNING（A-5 宽容分支：DISPATCHED 态补发 RUNNING 回调，再等 RUNNING）。 */
    private void pushToRunning(long executionId) throws Exception {
        String state = awaitExecutionState(executionId, 6000, "DISPATCHED", "RUNNING");
        if ("DISPATCHED".equals(state)) {
            ok(reportResult(executionId, "RUNNING", null), "回调 RUNNING 应成功");
        }
        assertThat(awaitExecutionState(executionId, 6000, "RUNNING"))
                .as("执行应进入 RUNNING，实际=%s", stateOf(executionId)).isEqualTo("RUNNING");
    }

    private long executionIdOf(String dispatchPath) {
        Matcher matcher = RUN_PATH.matcher(dispatchPath);
        assertThat(matcher.matches())
                .as("下发路径应照协议 /internal/v1/job/executions/{executionId}/run：%s", dispatchPath)
                .isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private void assertDispatchBody(String body, String expectedJobKey, String expectedHandler)
            throws IOException {
        JsonNode dispatch = JSON.readTree(body);
        assertThat(dispatch.path("jobKey").asText())
                .as("下发 body jobKey").isEqualTo(expectedJobKey);
        assertThat(dispatch.path("handler").asText())
                .as("下发 body handler").isEqualTo(expectedHandler);
        assertThat(dispatch.path("attempt").asInt())
                .as("下发 body attempt 首触=0").isZero();
        assertThat(dispatch.has("param")).as("下发 body param 在场").isTrue();
        assertThat(dispatch.path("traceId").asText()).as("下发 body traceId 非空").isNotBlank();
        assertThat(dispatch.has("fireTime")).as("下发 body fireTime 在场").isTrue();
        assertThat(dispatch.path("shardContext").path("index").asInt())
                .as("下发 body shardContext.index").isZero();
        assertThat(dispatch.path("shardContext").path("total").asInt())
                .as("下发 body shardContext.total（未分片=1）").isEqualTo(1);
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

    // ---------- AC-1：V1 四表 + 部分唯一索引 + Flyway 迁移成功 ----------

    @Test
    void should_prepare_scheduler_tables_and_partial_unique_index_when_v1_migrated() {
        // ① V1 四表就绪（确认③）
        for (String table : List.of("sch_job_definition", "sch_executor_registry",
                "sch_job_execution", "sch_scheduler_lock")) {
            Long rows = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            assertThat(rows).as("V1 表 %s 应就绪", table).isNotNull();
        }

        // ② 部分唯一索引 uk(def_id,fire_time) WHERE trigger_type='SCHEDULE' AND attempt=0
        //   （确认③钉死的同 fire_time 幂等机制；catalog 断言不猜列可空性）
        List<String> indexDefs = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema() "
                        + "and tablename = 'sch_job_execution'", String.class);
        assertThat(indexDefs).as("sch_job_execution 应有唯一索引承载同 fire_time 幂等")
                .anySatisfy(def -> {
                    String normalized = def.toLowerCase().replace(" ", "");
                    assertThat(normalized)
                            .contains("unique")
                            .contains("definition_id")
                            .contains("fire_time")
                            .contains("where")
                            .contains("'schedule'")
                            .contains("attempt=0");
                });

        // ③ Flyway V1 执行成功
        Long v1Rows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Long.class);
        assertThat(v1Rows).as("scheduler V1 迁移应已执行成功").isGreaterThan(0L);
    }

    // ---------- AC-2：创建默认 DISABLED / jobKey 唯一 / job_key 不可改 / CRON 语法守卫 ----------

    @Test
    void should_default_disable_and_guard_job_key_and_trigger_rules_when_job_managed()
            throws Exception {
        // ① 创建默认 DISABLED（确认④：创建默认 DISABLED、显式启用）
        String jobKey = uniq("jobdefault");
        long jobId = createJobOk(cronBody(jobKey, STUB_APP, "it.handler.daily", "0 0 3 * * *"), jobKey);
        assertThat(definitionStatus(jobId)).as("创建默认应 DISABLED").isEqualTo("DISABLED");
        // 触发面依据列须随创建持久化（cron_expr 缺失会让启用时 TriggerCalculator 无从计算）
        assertThat(cronExprOf(jobId)).as("创建应持久化 cronExpr").isEqualTo("0 0 3 * * *");

        // ② /status 专用端点显式启用
        enableJob(jobId);
        assertThat(definitionStatus(jobId)).as("/status 启用后应 ENABLED").isEqualTo("ENABLED");

        // ③ 重复 jobKey → 业务拒绝（wire 码=SCHEDULER_JOB_KEY_DUPLICATE，api 契约常量）
        JsonNode duplicate = callAsSuperAdmin(post(JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        cronBody(jobKey, STUB_APP, "it.handler.daily", "0 0 3 * * *"))));
        bizFail(duplicate, "重复 jobKey 应拒绝（DUPLICATE）");
        assertThat(duplicate.path("code").asText())
                .as("重复 jobKey wire 码应为 SCHEDULER_JOB_KEY_DUPLICATE")
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE);

        // ④ CRON 语法错 → 业务拒绝（SCHEDULER_TRIGGER_INVALID）且不落库
        String badCronKey = uniq("jobbadcron");
        ObjectNode badCron = cronBody(badCronKey, STUB_APP, "it.handler.bad", "not-a-cron");
        JsonNode badCronResp = callAsSuperAdmin(post(JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(badCron)));
        bizFail(badCronResp, "CRON 语法错应拒绝（TRIGGER_INVALID）");
        assertThat(badCronResp.path("code").asText())
                .as("CRON 语法错 wire 码应为 SCHEDULER_TRIGGER_INVALID")
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_TRIGGER_INVALID);
        Long badCronRows = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key = ?", Long.class, badCronKey);
        assertThat(badCronRows).as("非法 CRON 不得落库").isZero();

        // ⑤ job_key 不可改：PUT 携带不同 jobKey → 业务拒绝（SCHEDULER_JOB_KEY_IMMUTABLE）
        //   且数据不变（确认④/契约 AC-2：拒绝口径）
        ObjectNode rename = JSON.createObjectNode();
        rename.put("jobKey", uniq("jobrenamed"));
        rename.put("handler", "it.handler.daily");
        rename.put("timeoutMs", 123_456L);
        rename.put("version", jobVersion(jobId));
        JsonNode renameResp = callAsSuperAdmin(put(JOBS_URL + "/" + jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(rename)));
        bizFail(renameResp, "job_key 变更应拒绝（IMMUTABLE）");
        assertThat(renameResp.path("code").asText())
                .as("job_key 变更 wire 码应为 SCHEDULER_JOB_KEY_IMMUTABLE")
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_JOB_KEY_IMMUTABLE);
        assertThat(jobKeyOf(jobId)).as("拒绝后 job_key 不得变化").isEqualTo(jobKey);

        // ⑥ 合法更新（不含 jobKey）→ 成功且生效
        ObjectNode legit = JSON.createObjectNode();
        legit.put("handler", "it.handler.daily");
        legit.put("timeoutMs", 123_456L);
        legit.put("version", jobVersion(jobId));
        ok(callAsSuperAdmin(put(JOBS_URL + "/" + jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(legit))), "合法更新应成功");
        Long timeoutMs = jdbcTemplate.queryForObject(
                "select timeout_ms from sch_job_definition where id = ?", Long.class, jobId);
        assertThat(timeoutMs).as("合法更新应生效").isEqualTo(123_456L);
    }

    // ---------- AC-3（HTTP 面）：executions 分页过滤 + MANUAL 触发 ----------

    @Test
    void should_page_executions_filtered_by_job_key_when_querying_history() throws Exception {
        // FIXED_RATE 1s 任务；无执行器 → NO_EXECUTOR 快速终态（不污染分页计数），
        // SCHEDULE 行按秒累积，历史查询面可验过滤
        String jobKey = uniq("jobpage");
        long jobId = createJobOk(fixedRateBody(jobKey, GHOST_APP, "it.handler.page", 1000L, 1), jobKey);
        enableJob(jobId);

        assertThat(await(12_000, () -> scheduleCount(jobKey) >= 2))
                .as("应连续多 tick 触发生成 execution，实际=%d", scheduleCount(jobKey)).isTrue();

        JsonNode page = callAsSuperAdmin(get(EXECUTIONS_URL + "?jobKey=" + jobKey + "&page=1&size=50"));
        ok(page, "executions 分页应成功");
        List<JsonNode> items = dataItems(page);
        assertThat(items).as("jobKey 过滤结果非空").isNotEmpty();
        assertThat(items).as("过滤结果应只含该 job 的 execution")
                .allSatisfy(item -> assertThat(item.path("jobKey").asText()).isEqualTo(jobKey));
        assertThat(items).as("分页应≤size 上界（pitfalls：PageQuery 上界 200 内合法）").hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void should_create_manual_execution_when_admin_triggers_job() throws Exception {
        // /trigger=MANUAL execution（确认④，权限 job:update——超管短路持有）
        String jobKey = uniq("jobmanual");
        long jobId = createJobOk(
                fixedRateBody(jobKey, GHOST_APP, "it.handler.manual", 60_000L, 1), jobKey);
        enableJob(jobId);

        ok(callAsSuperAdmin(post(JOBS_URL + "/" + jobId + "/trigger")), "手动触发应成功");
        assertThat(await(8_000, () -> hasTriggerType(jobKey, "MANUAL")))
                .as("手动触发应生成 MANUAL execution").isTrue();
    }

    // ---------- AC-5：全链路状态机（桩执行器：下发 body → DISPATCHED → RUNNING → SUCCESS） ----------

    @Test
    void should_run_full_dispatch_state_machine_when_executor_accepts_and_reports() throws Exception {
        registerExecutor(uniq("inst-chain"));
        // 桩先切挂起场景再启用任务（防启用即触发、桩以 ACCEPT_OK 抢答的竞态）
        STUB.scenario(ExecutorStub.Scenario.HOLD_FOREVER);
        String jobKey = uniq("jobchain");
        long jobId = createJobOk(fixedRateBody(jobKey, STUB_APP, "it.handler.chain", 1000L, 2), jobKey);
        enableJob(jobId);

        // ① 观察到下发请求（协议路径+body 字段断言）
        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应向执行器下发 POST /internal/v1/job/executions/{id}/run").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        assertThat(dispatch.path())
                .as("下发路径应含 executionId").isEqualTo("/internal/v1/job/executions/" + executionId + "/run");
        assertDispatchBody(dispatch.body(), jobKey, "it.handler.chain");

        // ② 下发已发生、接单未完成：PENDING→DISPATCHED（TL 钉死链）
        String dispatched = awaitExecutionState(executionId, 4_000, "DISPATCHED");
        assertThat(dispatched).as("下发后接单前应处于 DISPATCHED（钉死链），实际=%s", stateOf(executionId))
                .isEqualTo("DISPATCHED");

        // ③ 非法跃迁：DISPATCHED 直接回调 SUCCESS → 拒绝（确认④ CAS：PENDING/DISPATCHED 不可直接 SUCCESS）
        JsonNode prematureSuccess = reportResult(executionId, "SUCCESS", null);
        bizFail(prematureSuccess, "DISPATCHED 直接 SUCCESS 应拒绝");
        assertThat(prematureSuccess.path("code").asText())
                .as("CAS 不命中 wire 码应为 SCHEDULER_EXECUTION_STATE_CONFLICT")
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_EXECUTION_STATE_CONFLICT);
        assertThat(stateOf(executionId)).as("拒绝后状态不变").isEqualTo("DISPATCHED");

        // ④ 放行接单（200 {accepted:true}）→ 回调 RUNNING → 回调 SUCCESS
        STUB.releaseHeld(200, ACCEPTED_BODY);
        String afterAccept = awaitExecutionState(executionId, 4_000, "DISPATCHED", "RUNNING");
        if ("DISPATCHED".equals(afterAccept)) {
            // A-5 宽容分支：实现若在接单后仍停 DISPATCHED，按钉死链由回调推进 RUNNING
            ok(reportResult(executionId, "RUNNING", null), "回调 RUNNING 应成功");
        }
        assertThat(awaitExecutionState(executionId, 4_000, "RUNNING"))
                .as("接单后应 RUNNING，实际=%s", stateOf(executionId)).isEqualTo("RUNNING");
        ok(reportResult(executionId, "SUCCESS", null), "回调 SUCCESS 应成功");
        assertThat(awaitExecutionState(executionId, 4_000, "SUCCESS"))
                .as("回调后应 SUCCESS，实际=%s", stateOf(executionId)).isEqualTo("SUCCESS");

        // ⑤ 终态后再回调 → CAS 拒绝
        JsonNode repeated = reportResult(executionId, "SUCCESS", null);
        bizFail(repeated, "终态后重复 SUCCESS 应拒绝");
        assertThat(repeated.path("code").asText())
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_EXECUTION_STATE_CONFLICT);
        JsonNode lateFailed = reportResult(executionId, "FAILED", "late-callback");
        bizFail(lateFailed, "终态后 FAILED 应拒绝");
        assertThat(lateFailed.path("code").asText())
                .isEqualTo(SchedulerErrorCodes.SCHEDULER_EXECUTION_STATE_CONFLICT);
        assertThat(stateOf(executionId)).as("终态不得被改写").isEqualTo("SUCCESS");
    }

    // ---------- AC-6：接单超时 / NO_EXECUTOR / 回调 FAILED → RETRY；耗尽 → 终态 ----------

    @Test
    void should_retry_with_incremented_attempt_when_dispatch_accept_timeout() throws Exception {
        registerExecutor(uniq("inst-dt"));
        // 桩挂起不响应 → 接单超时 10s 未 200 → FAILED(DISPATCH_TIMEOUT) → RETRY attempt=1
        STUB.scenario(ExecutorStub.Scenario.HOLD_FOREVER);
        String jobKey = uniq("jobdtimeout");
        long jobId = createJobOk(
                fixedRateBody(jobKey, STUB_APP, "it.handler.dt", 1_000L, 2), jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应发生下发（随后被桩挂起）").isNotNull();
        long executionId = executionIdOf(dispatch.path());

        boolean timedOut = await(20_000, () -> "FAILED".equals(stateOf(executionId))
                && errorOf(executionId) != null
                && errorOf(executionId).contains("DISPATCH_TIMEOUT"));
        assertThat(timedOut)
                .as("接单超时应 FAILED(DISPATCH_TIMEOUT)，实际 state=%s error=%s",
                        stateOf(executionId), errorOf(executionId))
                .isTrue();
        assertThat(await(8_000, () -> hasRetryAttempt(jobKey, 1)))
                .as("接单超时应按 retry_policy 生成 RETRY execution attempt=1").isTrue();
    }

    @Test
    void should_retry_when_no_executor_online() throws Exception {
        // 无在线执行器（appName 不在发现层）→ FAILED(NO_EXECUTOR) → RETRY attempt=1
        String jobKey = uniq("jobnoexec");
        long jobId = createJobOk(
                fixedRateBody(jobKey, GHOST_APP, "it.handler.ne", 1_000L, 2), jobKey);
        enableJob(jobId);

        boolean failed = await(12_000, () -> "FAILED".equals(latestScheduleState(jobKey))
                && latestScheduleError(jobKey) != null
                && latestScheduleError(jobKey).contains("NO_EXECUTOR"));
        assertThat(failed)
                .as("无在线执行器应 FAILED(NO_EXECUTOR)，实际 state=%s error=%s",
                        latestScheduleState(jobKey), latestScheduleError(jobKey))
                .isTrue();
        assertThat(await(8_000, () -> hasRetryAttempt(jobKey, 1)))
                .as("NO_EXECUTOR 应走重试生成 RETRY attempt=1").isTrue();
    }

    @Test
    void should_retry_when_executor_reports_failed() throws Exception {
        registerExecutor(uniq("inst-cf"));
        STUB.scenario(ExecutorStub.Scenario.ACCEPT_OK);
        String jobKey = uniq("jobcbfailed");
        long jobId = createJobOk(
                fixedRateBody(jobKey, STUB_APP, "it.handler.cf", 1_000L, 2), jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应发生下发").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        pushToRunning(executionId);

        // 执行回调 FAILED → 按 retry_policy 生成 RETRY attempt=1
        ok(reportResult(executionId, "FAILED", "it-error-071002"), "回调 FAILED 应成功");
        assertThat(awaitExecutionState(executionId, 6_000, "FAILED"))
                .as("回调后应 FAILED，实际=%s", stateOf(executionId)).isEqualTo("FAILED");
        assertThat(await(8_000, () -> hasRetryAttempt(jobKey, 1)))
                .as("执行 FAILED 应生成 RETRY execution attempt=1").isTrue();
    }

    @Test
    void should_settle_failed_terminal_when_retry_attempts_exhausted() throws Exception {
        registerExecutor(uniq("inst-ex"));
        // 桩恒 500（未 200=下发失败）→ 重试链走完 → 耗尽终态 FAILED（ONCE 任务不重触发，链路自闭合）
        STUB.scenario(ExecutorStub.Scenario.REJECT_500);
        String jobKey = uniq("jobexhaust");
        ObjectNode body = onceBody(jobKey, STUB_APP, "it.handler.ex", Instant.now().plusSeconds(1),
                "FIRE_ONCE_NOW");
        int maxAttempts = 1;
        ((ObjectNode) body.get("retryPolicy")).put("maxAttempts", maxAttempts);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        // ① 观察窗（2.5s）——attempt 水位口径：窗内该 jobKey 全部 execution 行的
        //    max(attempt) 恒 <= maxAttempts（本用例=1，与原「无 attempt>=2 行」子条件等价），
        //    保留「耗尽后不得再生成更高 attempt」的断言本意；原口径的行数冻结子条件
        //    （total==rowsAtSettle）被剔除——它会被「源行 FAILED 落库 → 子 RETRY 行
        //    合法 INSERT」的毫秒级间隙误击（round-1 门禁归因：用例夹具竞态，非实现回归）；
        //    水位对重试链异步延伸在途免疫：合法续期行（RETRY attempt=1）落库不越界。
        boolean attemptWithinMax = staysTrueFor(2_500, () -> maxAttemptOf(jobKey) <= maxAttempts);
        assertThat(attemptWithinMax)
                .as("重试链全程不得生成 attempt 超过 maxAttempts（本用例=%d）的 execution，"
                        + "观察窗末实际水位=%d", maxAttempts, maxAttemptOf(jobKey))
                .isTrue();

        // ② 窗末断言：全部 execution 行到达终态 FAILED——源行 + 合法续期行都已 settle。
        //    本夹具 maxAttempts=1，重试链恰产生源行 + 1 条 RETRY 续期行（同 reactor 阳性
        //    对照 REQ20261122150756 耗尽腿同款夹具），故 settle 以 total >= 2 且全部
        //    FAILED 为准：total=1 只是「源行已 FAILED、续期行在途」的间隙态，不得据此
        //    提前判定 settle（round-1 竞态入口，await 继续轮询至真 settle）。
        boolean settled = await(20_000, () -> {
            Long total = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_execution where job_key = ?", Long.class, jobKey);
            Long failedTotal = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_execution where job_key = ? and state = 'FAILED'",
                    Long.class, jobKey);
            return total != null && failedTotal != null && total >= 2 && total.equals(failedTotal);
        });
        assertThat(settled)
                .as("重试耗尽后该任务全部 execution（源行+续期行）应终态 FAILED").isTrue();

        // ③ 窗末后再观察 2s：链已收敛——行数不再变化（无新增 execution），且 attempt
        //    水位仍不越界（若重试链慢于①观察窗结束才走完，settle 后仍可捕获越界续期）。
        long rowsAtConvergence = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ?", Long.class, jobKey);
        boolean converged = staysTrueFor(2_000, () -> {
            Long total = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_execution where job_key = ?", Long.class, jobKey);
            return total != null && total == rowsAtConvergence
                    && maxAttemptOf(jobKey) <= maxAttempts;
        });
        assertThat(converged)
                .as("链收敛后 2s 内行数不得变化且 attempt 水位不得超过 maxAttempts=%d"
                        + "（窗末行数=%d）", maxAttempts, rowsAtConvergence)
                .isTrue();
    }

    // ---------- AC-9：同 job 串行（上一 execution 未终态不触发下一次 SCHEDULE） ----------

    @Test
    void should_not_trigger_next_schedule_while_previous_execution_unfinished() throws Exception {
        registerExecutor(uniq("inst-serial"));
        STUB.scenario(ExecutorStub.Scenario.HOLD_FOREVER);
        String jobKey = uniq("jobserial");
        long jobId = createJobOk(
                fixedRateBody(jobKey, STUB_APP, "it.handler.serial", 1000L, 1), jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("首触应下发（随后被桩挂起、execution 停留未终态）").isNotNull();

        long firstCount = scheduleCount(jobKey);
        assertThat(firstCount).as("首触发恰生成 1 条 SCHEDULE execution").isEqualTo(1L);

        // 下发挂起（execution 未终态）期间跨 3 个 tick：不得触发下一次 SCHEDULE
        boolean serial = staysTrueFor(3_500, () -> scheduleCount(jobKey) == 1);
        assertThat(serial)
                .as("上一 execution 未终态期间不得触发下一次 SCHEDULE（串行），实际=%d",
                        scheduleCount(jobKey))
                .isTrue();
    }

    // ---------- AC-8：RUNNING 超时巡检 TIMEOUT → 走重试策略 ----------

    @Test
    void should_mark_timeout_and_retry_when_running_exceeds_timeout_ms() throws Exception {
        registerExecutor(uniq("inst-to"));
        STUB.scenario(ExecutorStub.Scenario.ACCEPT_OK);
        String jobKey = uniq("jobtimeout");
        ObjectNode body = fixedRateBody(jobKey, STUB_APP, "it.handler.to", 1_000L, 3);
        body.put("timeoutMs", 1_000L);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应发生下发").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        pushToRunning(executionId);

        // 巡检：RUNNING 且 now-fire_time > timeout_ms → TIMEOUT
        assertThat(await(15_000, () -> "TIMEOUT".equals(stateOf(executionId))))
                .as("RUNNING 超时应被巡检标记 TIMEOUT，实际=%s", stateOf(executionId)).isTrue();

        // 走重试策略：RETRY execution attempt=1
        assertThat(await(8_000, () -> hasRetryAttempt(jobKey, 1)))
                .as("TIMEOUT 应按重试策略生成 RETRY execution attempt=1").isTrue();
    }

    // ---------- AC-10：注册 / 心跳 / 30s 失联 OFFLINE ----------

    @Test
    void should_register_executor_and_refresh_heartbeat_when_internal_calls_received()
            throws Exception {
        String instanceId = uniq("inst-hb");

        // ① 注册入库（uk(app_name,instance_id)；ONLINE；heartbeat_at 刷新）
        registerExecutor(instanceId);
        Map<String, Object> row = registryRow(STUB_APP, instanceId);
        assertThat(row).as("注册应落 sch_executor_registry").isNotNull();
        assertThat(row.get("status")).as("注册态应 ONLINE").isEqualTo("ONLINE");

        // ② 重复注册（同 app+instance）→ upsert 不重复行
        registerExecutor(instanceId);
        assertThat(registryRowCount(STUB_APP, instanceId)).as("重复注册不产生第二行").isEqualTo(1L);

        // ③ 心跳刷新：夹具造 60s 旧值 → heartbeat 拉回当下（heartbeat_at 可 UPDATE 夹具造旧值）
        jdbcTemplate.update(
                "update sch_executor_registry set heartbeat_at = now() - interval '60 seconds' "
                        + "where app_name = ? and instance_id = ?", STUB_APP, instanceId);
        ObjectNode heartbeat = JSON.createObjectNode();
        heartbeat.put("appName", STUB_APP);
        heartbeat.put("instanceId", instanceId);
        ok(internalCall(post(HEARTBEAT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(heartbeat))), "心跳应成功");
        boolean refreshed = await(5_000, () -> {
            Map<String, Object> current = registryRow(STUB_APP, instanceId);
            if (current == null) {
                return false;
            }
            Long fresh = jdbcTemplate.queryForObject(
                    "select count(*) from sch_executor_registry where app_name = ? "
                            + "and instance_id = ? and heartbeat_at > now() - interval '10 seconds'",
                    Long.class, STUB_APP, instanceId);
            return fresh != null && fresh > 0 && "ONLINE".equals(current.get("status"));
        });
        assertThat(refreshed).as("心跳应刷新 heartbeat_at 并回 ONLINE").isTrue();
    }

    @Test
    void should_mark_executor_offline_when_heartbeat_stale_beyond_30s() throws Exception {
        String instanceId = uniq("inst-off");
        registerExecutor(instanceId);

        // 夹具造 stale 31s（> 失联阈值 30s）→ executors 列表在线状态应呈 OFFLINE
        jdbcTemplate.update(
                "update sch_executor_registry set heartbeat_at = now() - interval '31 seconds' "
                        + "where app_name = ? and instance_id = ?", STUB_APP, instanceId);
        boolean offline = await(12_000, () -> {
            try {
                return "OFFLINE".equals(executorListStatus(STUB_APP, instanceId));
            } catch (Exception ex) {
                return false;
            }
        });
        assertThat(offline)
                .as("心跳 stale>30s 应 OFFLINE，实际=%s", executorListStatus(STUB_APP, instanceId))
                .isTrue();

        // 心跳恢复 → ONLINE
        ObjectNode heartbeat = JSON.createObjectNode();
        heartbeat.put("appName", STUB_APP);
        heartbeat.put("instanceId", instanceId);
        ok(internalCall(post(HEARTBEAT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(heartbeat))), "恢复心跳应成功");
        boolean online = await(8_000, () -> {
            try {
                return "ONLINE".equals(executorListStatus(STUB_APP, instanceId));
            } catch (Exception ex) {
                return false;
            }
        });
        assertThat(online).as("恢复心跳后应回 ONLINE").isTrue();
    }

    // ---------- AC-11：无权限访问 admin 面 → 403（既有 RBAC 口径） ----------

    @Test
    void should_reject_admin_surface_without_scheduler_permission_with_403() throws Exception {
        // 无权限装配（MessageApi/套餐五端点先例）：令牌角色无 sys_role 绑定 → 权限解析空集；
        // 跨域判定桩本用例内切拒绝（hasPermission=false → fail-closed 403，同授权面真实口径）
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", false));
        Set<String> noPermissionRoles = Set.of(uniq("unbound_role"));
        MvcResult result = performAs("1", "default", noPermissionRoles, get(JOBS_URL));
        assertThat(result.getResponse().getStatus())
                .as("无 scheduler:job:list 权限访问 admin 面应 403（PERMISSION_DENIED 登记 403）")
                .isEqualTo(403);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("403 以 Result 信封表达").isFalse();
        assertThat(body.path("code").asText()).as("错误码应非 0").isNotEqualTo("0");
    }

    // ---------- AC-12：内部一次性任务注册（BPM 开通位）→ ONCE 触发下发 ----------

    @Test
    void should_register_once_job_internally_and_dispatch_at_fire_time() throws Exception {
        registerExecutor(uniq("inst-bpm"));
        STUB.scenario(ExecutorStub.Scenario.ACCEPT_OK);
        String jobKey = uniq("bpmonce");

        // ① 注册即返回（Result 信封成功；一次性注册契约=OneShotJobRegisterRequest：
        // jobKey/appName/handler/onceFireAt/param(String)/timeoutMs/retryMaxAttempts/retryInitialIntervalMs；
        // trigger 隐含 ONCE，确认④）
        ObjectNode registration = JSON.createObjectNode();
        registration.put("jobKey", jobKey);
        registration.put("appName", STUB_APP);
        registration.put("handler", "bpm_open_handler");
        registration.put("onceFireAt", Instant.now().plusSeconds(2).toString());
        registration.put("param", "{\"processDefinitionId\":\"it-071002-proc\"}");
        registration.put("timeoutMs", 60_000L);
        registration.put("retryMaxAttempts", 1);
        registration.put("retryInitialIntervalMs", 100);
        JsonNode resp = internalCall(post(INTERNAL_JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(registration)));
        ok(resp, "一次性任务注册应成功");

        // ② ONCE 定义落库
        Long definitionRows = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key = ? and trigger_type = 'ONCE'",
                Long.class, jobKey);
        assertThat(definitionRows).as("注册应落 ONCE 定义").isEqualTo(1L);

        // ③ fireTime 到点触发下发（协议路径 + body jobKey）
        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("ONCE 到点应触发下发（BPM 开通位实证）").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        assertThat(dispatch.path())
                .isEqualTo("/internal/v1/job/executions/" + executionId + "/run");
        assertDispatchBody(dispatch.body(), jobKey, "bpm_open_handler");

        // ④ 一次性：SCHEDULE attempt=0 恰 1 条且不重复
        assertThat(scheduleCount(jobKey)).as("ONCE 应恰生成 1 条 SCHEDULE execution").isEqualTo(1L);
        assertThat(staysTrueFor(2_000, () -> scheduleCount(jobKey) == 1))
                .as("ONCE 不得重复触发，实际=%d", scheduleCount(jobKey)).isTrue();
    }

    // ---------- AC-13：网关路由 + 聚合文档分组（源级断言，A-5 手段如实上报） ----------

    @Test
    void should_route_scheduler_surface_and_doc_group_without_internal_exposure()
            throws IOException {
        // AC-13（委派单口径：网关配置断言，源级）：路由段 + springdoc 聚合分组 + /internal 不在路由
        String gatewayYml = readSource("acme-gateway/src/main/resources/application.yml");
        assertThat(gatewayYml)
                .as("网关应含 /admin/v1/scheduler/** → lb://platform-scheduler 路由（确认⑥）")
                .contains("Path=/admin/v1/scheduler/**")
                .contains("lb://platform-scheduler");
        assertThat(gatewayYml)
                .as("网关应含调度中心聚合文档分组 /v3/api-docs/scheduler（spec §1 springdoc 分组）")
                .contains("/v3/api-docs/scheduler");
        assertThat(gatewayYml)
                .as("/internal/** 不得进网关路由白名单（spec §4 内部端点安全；internal 面=服务间直连）")
                .doesNotContain("Path=/internal");

        // scheduler 服务配置面在场（端口 9884；springdoc 分组归网关聚合面，服务侧为 springdoc 缺省）
        Path schedulerYml = acmeCloudRoot().resolve(
                "acme-platform/platform-scheduler/platform-scheduler-service/src/main/resources/application.yml");
        assertThat(Files.exists(schedulerYml))
                .as("scheduler 服务 application.yml 应存在（端口 9884 配置面）").isTrue();
    }

    // ---------- AC-14：bootstrap 聚合（轻量源级断言，手段如实上报） ----------

    @Test
    void should_aggregate_scheduler_module_into_bootstrap() throws IOException {
        // ① acme-platform pom 登记 platform-scheduler 模块（确认⑥变更清单）
        String platformPom = readSource("acme-platform/pom.xml");
        assertThat(platformPom)
                .as("acme-platform pom 应登记 platform-scheduler 模块")
                .contains("<module>platform-scheduler</module>");

        // ② bootstrap pom 聚合 scheduler 服务模块（确认⑥：bootstrap pom 加模块）
        String bootstrapPom = readSource("acme-bootstrap/pom.xml");
        assertThat(bootstrapPom)
                .as("acme-bootstrap pom 应含 platform-scheduler-service 依赖")
                .contains("platform-scheduler-service");

        // ③ 单体主类扫描面覆盖 com.acme.platform（组件面随扫描聚合；三 Application @Import 同理）
        String monolith = readSource("acme-bootstrap/src/main/java/com/acme/bootstrap/MonolithApplication.java");
        assertThat(monolith)
                .as("单体扫描面应覆盖 com.acme.platform（scheduler 组件随扫描聚合）")
                .contains("com.acme.platform");

        // ④ scheduler 服务主类文件在场（主类存在性——不做全上下文启动，装配代价以轻量断言替代）
        assertThat(Files.exists(acmeCloudRoot().resolve(
                "acme-platform/platform-scheduler/platform-scheduler-service/src/main/java/"
                        + "com/acme/platform/scheduler/PlatformSchedulerApplication.java")))
                .as("scheduler 服务主类文件应存在（A-1 惯例推导路径）").isTrue();
    }

    // ---------- 源文件定位支撑（AC-13/14） ----------

    /** 从 user.dir 向上定位 acme-cloud 根（含 acme-gateway 目录）。 */
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

    private static String readSource(String relativePath) throws IOException {
        Path path = acmeCloudRoot().resolve(relativePath);
        assertThat(Files.exists(path))
                .as("源文件缺失（backend 未落盘或路径不符，疑点上报）：%s", path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ---------- 内嵌执行器桩（JDK HttpServer；照 /internal/v1/job/executions/{id}/run 协议） ----------

    /** 下发请求记录。 */
    record RecordedRequest(String method, String path, String body, Instant receivedAt) {
    }

    /**
     * 执行器桩：记录全部请求；场景可切——立即接单 200/挂起接单（释放前不响应，用于
     * 接单超时与中间态断言）/恒 500（下发失败）。
     */
    static final class ExecutorStub {

        enum Scenario { ACCEPT_OK, HOLD_FOREVER, REJECT_500 }

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<Scenario> scenario = new AtomicReference<>(Scenario.ACCEPT_OK);
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger releaseCode = new AtomicInteger(200);
        private final AtomicReference<String> releaseBody = new AtomicReference<>(ACCEPTED_BODY);
        private final AtomicReference<CountDownLatch> holdGate =
                new AtomicReference<>(new CountDownLatch(1));

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

        void scenario(Scenario next) {
            scenario.set(next);
            sequence.set(0);
        }

        /** 释放挂起的接单请求（AC-5 放行 200 接单面）。 */
        void releaseHeld(int code, String body) {
            releaseCode.set(code);
            releaseBody.set(body);
            holdGate.get().countDown();
        }

        /** 每用例复位：释放挂起请求（废弃态 500）、清记录、切场景。 */
        void reset(Scenario next) {
            releaseCode.set(500);
            holdGate.get().countDown();
            CountDownLatch freshGate = new CountDownLatch(1);
            holdGate.set(freshGate);
            releaseCode.set(200);
            releaseBody.set(ACCEPTED_BODY);
            requests.clear();
            scenario.set(next);
            sequence.set(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            RecordedRequest recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    Instant.now());
            requests.add(recorded);
            try {
                switch (scenario.get()) {
                    case REJECT_500 -> respond(exchange, 500, "{\"error\":\"stub-reject\"}");
                    case HOLD_FOREVER -> {
                        CountDownLatch gate = holdGate.get();
                        gate.await();
                        respond(exchange, releaseCode.get(), releaseBody.get());
                    }
                    default -> respond(exchange, 200, ACCEPTED_BODY);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException clientGone) {
                // 调度侧接单超时后断开：释放时对端已关，忽略
                exchange.close();
            }
        }

        private void respond(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
