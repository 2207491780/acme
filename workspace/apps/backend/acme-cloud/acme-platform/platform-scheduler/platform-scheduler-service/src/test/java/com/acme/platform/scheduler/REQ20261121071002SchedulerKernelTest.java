package com.acme.platform.scheduler;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-071002 调度内核时序测试（Testcontainers PG；装配照
 * {@code REQ20261121071002SchedulerCoreIntegrationTest}/{@code SystemIntegrationTest} 惯例；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-3 / AC-4 / AC-7 内核语义（可控短周期：FIXED_RATE 1s 级任务 + 轮询断言；
 * 全部以「库中真实状态」推演，执行面 HTTP 桩不在本类——本类任务appName 指向不在发现层的
 * ghost 应用，触发→生成 execution 后经 NO_EXECUTOR 快速终态，不阻塞 tick 节奏）：</p>
 * <ul>
 *   <li>AC-3：CRON / FIXED_RATE / ONCE 三种 trigger 到达触发点生成 SCHEDULE execution；
 *       同 fire_time 幂等（多 tick 连续触发后按 (job_key, fire_time) 分组无重复——
 *       部分唯一索引 uk(def_id,fire_time) WHERE SCHEDULE+attempt=0 的 catalog 断言在
 *       SchedulerCoreIntegrationTest.should_prepare_scheduler_tables_and_partial_unique_index_when_v1_migrated）。</li>
 *   <li>AC-4：ShedLock 选主语义——锁竞争断言装配（如实报告）：单 Spring 上下文 + 锁行外部占用
 *       模拟另一实例持有（sch_scheduler_lock.lock_until 推至未来、locked_by 改他节点），
 *       断言持锁窗口内本实例 tick 停摆（无新 execution）、锁过期后接管恢复触发。
 *       两上下文对啄装配代价高（双容器/双 JVM），委派单允许「锁竞争断言」口径。</li>
 *   <li>AC-7：misfire 两策略——FIRE_ONCE_NOW（默认）补跑一次且不重复 / IGNORE 跳过错过的
 *       触发不补跑（用 once_fire_at 已过期的 ONCE 任务实测，TL 钉死手段；IGNORE 腿带
 *       FIXED_RATE 对照任务证明内核存活、断言不空洞）。</li>
 * </ul>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 主应用类=com.acme.platform.scheduler.PlatformSchedulerApplication（同核心集成类 A-1）；
 * A-2 tick 主循环整循环（时间轮取任务+DB 兜底扫描）均在 ShedLock 锁内执行（spec §3.2
 *     「主循环单发锁」）——若实现只锁 DB 兜底、时间轮裸奔，本类 AC-4 失败即 spec 偏差上报；
 * A-3 管理/内部端点请求体形状按 V1 列名驼峰推导（同核心集成类 A-3）。</p>
 */
@SpringBootTest(classes = PlatformSchedulerApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-scheduler-kernel-it-secret-0123"
})
class REQ20261121071002SchedulerKernelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String JOBS_URL = "/admin/v1/scheduler/jobs";

    /** 不在发现层的 ghost 应用（触发→生成 execution→NO_EXECUTOR 快速终态，不阻塞 tick）。 */
    private static final String GHOST_APP = "acme-ghost-app-kernel";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl("acme_sched_kernel", "public"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨域权限判定桩（核心集成类同口径：单服务测试域无 platform-system 实例，缺省放行）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    /** ShedLock 供应器（teardown 删行后清缓存，防误判锁被持——infra JobSchedule 先例口径）。 */
    @Autowired(required = false)
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        org.mockito.Mockito.when(authorizationClient.hasPermission(
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据：任务定义走 admin DELETE 契约面（内核同步卸载时间轮条目，
        // 防悬空轮条目开火已删定义中断 tick 链）；execution/registry 行 SQL 物理清；
        // 锁行不删（删行与在途 tick 获取存在竞态），仅复位 lock_until 已过期（AC-4 会改写锁行）
        try {
            for (Long jobId : jdbcTemplate.queryForList("select id from sch_job_definition", Long.class)) {
                try {
                    callAsSuperAdmin(delete(JOBS_URL + "/" + jobId));
                } catch (Exception cleanupFailure) {
                    // 尽力清理，单个失败不阻断
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
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    // ---------- 夹具装配 ----------

    private String uniq(String prefix) {
        return "it071002k" + SEQ.incrementAndGet() + prefix;
    }

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

    private ObjectNode cronBody(String jobKey, String appName, String handler, String cronExpr) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "CRON");
        node.put("cronExpr", cronExpr);
        return node;
    }

    private ObjectNode fixedRateBody(String jobKey, String appName, String handler, long rateMs) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "FIXED_RATE");
        node.put("fixedRateMs", rateMs);
        return node;
    }

    private ObjectNode onceBody(String jobKey, String appName, String handler, Instant fireAt,
                                String misfirePolicy) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "ONCE");
        node.put("onceFireAt", fireAt.toString());
        node.put("misfirePolicy", misfirePolicy);
        return node;
    }

    private long createJobOk(ObjectNode body, String jobKey) throws Exception {
        JsonNode resp = callAsSuperAdmin(post(JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
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

    // ---------- HTTP 支撑 ----------

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                userId, tenant, Channel.B, roles)))
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
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    // ---------- DB 读取支撑 ----------

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

    /** SCHEDULE attempt=0 的 execution 行数（触发/幂等/串行断言统一口径）。 */
    private long scheduleCount(String jobKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Long.class, jobKey);
        return count == null ? 0L : count;
    }

    // ---------- 轮询支撑（自旋 + 余量，不 sleep 死等） ----------

    private static boolean await(long timeoutMs, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(100);
        }
        return condition.getAsBoolean();
    }

    private static boolean staysTrueFor(long windowMs, java.util.function.BooleanSupplier condition) {
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

    // ---------- AC-3：三种 trigger 触发生成 execution ----------

    @Test
    void should_generate_schedule_executions_for_cron_fixed_rate_and_once_when_fire_due()
            throws Exception {
        // CRON 每秒（6 位域）；FIXED_RATE 1s；ONCE now+2s——三者均 ENABLED 后待触发
        String cronKey = uniq("cron");
        long cronId = createJobOk(cronBody(cronKey, GHOST_APP, "it.handler.cron", "* * * * * *"), cronKey);
        String rateKey = uniq("rate");
        long rateId = createJobOk(fixedRateBody(rateKey, GHOST_APP, "it.handler.rate", 1000L), rateKey);
        String onceKey = uniq("once");
        long onceId = createJobOk(
                onceBody(onceKey, GHOST_APP, "it.handler.once", Instant.now().plusSeconds(2),
                        "FIRE_ONCE_NOW"), onceKey);
        enableJob(cronId);
        enableJob(rateId);
        enableJob(onceId);

        // 到达触发点 → 各生成 SCHEDULE execution（tick=1s 主循环 + DB 兜底扫描，留 12s 余量）
        assertThat(await(12_000, () -> scheduleCount(cronKey) >= 1))
                .as("CRON 触发应生成 SCHEDULE execution").isTrue();
        assertThat(await(12_000, () -> scheduleCount(rateKey) >= 1))
                .as("FIXED_RATE 触发应生成 SCHEDULE execution").isTrue();
        assertThat(await(12_000, () -> scheduleCount(onceKey) >= 1))
                .as("ONCE 到点应生成 SCHEDULE execution").isTrue();

        // ONCE 一次性：恰 1 条且观察窗内不重复
        assertThat(scheduleCount(onceKey)).as("ONCE 恰生成 1 条").isEqualTo(1L);
        assertThat(staysTrueFor(2_000, () -> scheduleCount(onceKey) == 1))
                .as("ONCE 不得重复触发，实际=%d", scheduleCount(onceKey)).isTrue();
    }

    // ---------- AC-3：同 fire_time 幂等（重复 tick 不重复） ----------

    @Test
    void should_keep_one_execution_per_fire_time_when_ticks_repeat() throws Exception {
        String jobKey = uniq("dedup");
        long jobId = createJobOk(fixedRateBody(jobKey, GHOST_APP, "it.handler.dup", 1000L), jobKey);
        enableJob(jobId);

        // 连续多 tick 触发（≥3 个不同 fire_time）
        assertThat(await(12_000, () -> scheduleCount(jobKey) >= 3))
                .as("应连续多 tick 触发，实际=%d", scheduleCount(jobKey)).isTrue();

        // 同 fire_time 不重复（部分唯一索引幂等）：按 fire_time 分组无 >1 行
        List<Map<String, Object>> duplicated = jdbcTemplate.queryForList(
                "select fire_time, count(*) as cnt from sch_job_execution "
                        + "where job_key = ? and trigger_type = 'SCHEDULE' and attempt = 0 "
                        + "group by fire_time having count(*) > 1", jobKey);
        assertThat(duplicated)
                .as("同一 fire_time 不得生成重复 execution（重复 tick 幂等），重复组=%s", duplicated)
                .isEmpty();

        // 行为面交叉验证：execution 总数 = 去重 fire_time 数（每 fire_time 恰一条）
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Long.class, jobKey);
        Long distinctFireTimes = jdbcTemplate.queryForObject(
                "select count(distinct fire_time) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Long.class, jobKey);
        assertThat(rows)
                .as("execution 行数应与去重 fire_time 数一致（rows=%d, distinct=%d）",
                        rows, distinctFireTimes)
                .isEqualTo(distinctFireTimes);
    }

    // ---------- AC-4：ShedLock 选主（锁竞争断言装配） ----------

    @Test
    void should_block_and_resume_ticking_when_lock_holder_competes() throws Exception {
        String jobKey = uniq("lock");
        long jobId = createJobOk(fixedRateBody(jobKey, GHOST_APP, "it.handler.lock", 1000L), jobKey);
        enableJob(jobId);

        // 锁空闲期：tick 正常触发（同时等待 ShedLock 锁行建立）
        assertThat(await(12_000, () -> scheduleCount(jobKey) >= 1))
                .as("锁空闲期应正常触发").isTrue();
        String lockName = awaitTickLockRowName(5_000);
        assertThat(lockName)
                .as("ShedLock 锁行应已建立（tick 单发锁，JDBC 口径）").isNotNull();

        // 模拟另一实例占锁 8s（lock_until 推至未来、locked_by 改他节点）。
        // 时刻表达式必须 UTC 墙钟（timezone('utc', now())）与 ShedLock usingDbTime 同参照系，
        // 裸 now() 绝对时刻经会话时区重解释 = ShedLock 视界 +8h（V1 DDL 注释同口径）。
        jdbcTemplate.update(
                "update sch_scheduler_lock set locked_by = 'it-ghost-node', "
                        + "locked_at = timezone('utc', now()), "
                        + "lock_until = timezone('utc', now()) + interval '8 seconds' where name = ?", lockName);
        // 固定 1.5s 收尾静默（确定性 setup 停顿，非验证等待）：吸收占锁前已获锁的在途 tick，
        // 其后 3s 观察窗计数恒定 = tick 停摆（窗口 T+1.5~T+4.5 < 占锁 8s）
        sleepQuietly(1_500);
        long frozen = scheduleCount(jobKey);
        boolean frozenStable = staysTrueFor(3_000, () -> scheduleCount(jobKey) == frozen);
        assertThat(frozenStable)
                .as("他节点持锁期间本实例不得触发（ShedLock 单发），frozen=%d 实际=%d",
                        frozen, scheduleCount(jobKey))
                .isTrue();

        // 锁过期 → 本实例接管，恢复触发（UTC 墙钟写法，同上参照系纪律）
        jdbcTemplate.update(
                "update sch_scheduler_lock set lock_until = timezone('utc', now()) - interval '1 seconds' "
                        + "where name = ?", lockName);
        assertThat(await(8_000, () -> scheduleCount(jobKey) > frozen))
                .as("锁过期后应接管并恢复触发（接管前=%d，实际=%d）",
                        frozen, scheduleCount(jobKey))
                .isTrue();
    }

    /** 等待 tick 运行实例建立 ShedLock 锁行并返回锁名（行名与 {@link com.acme.platform.scheduler.kernel.SchedulerTickJob}
     * 的 @SchedulerLock 注解常量单源，不做全表任取——锁表同存 tick/patrol 两行，无序 select 的
     * get(0) 会随机抓到 patrol 行致占锁打偏，round-2 实测复现）。 */
    private String awaitTickLockRowName(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> names = jdbcTemplate.queryForList(
                    "select name from sch_scheduler_lock where name = ?", String.class,
                    com.acme.platform.scheduler.kernel.SchedulerTickJob.TICK_LOCK_NAME);
            if (!names.isEmpty()) {
                return names.get(0);
            }
            sleepQuietly(100);
        }
        return null;
    }

    // ---------- AC-7：misfire 两策略（once_fire_at 已过期的 ONCE 任务实测） ----------

    @Test
    void should_fire_missed_once_trigger_once_when_misfire_policy_fire_once_now() throws Exception {
        // FIRE_ONCE_NOW（默认）：错过的触发补跑一次，且不得每 tick 重复补跑
        String jobKey = uniq("misfireonce");
        long jobId = createJobOk(
                onceBody(jobKey, GHOST_APP, "it.handler.misfire", Instant.now().minusSeconds(5),
                        "FIRE_ONCE_NOW"), jobKey);
        enableJob(jobId);

        assertThat(await(12_000, () -> scheduleCount(jobKey) >= 1))
                .as("FIRE_ONCE_NOW 应补跑一次").isTrue();
        assertThat(scheduleCount(jobKey)).as("补跑恰好一次").isEqualTo(1L);
        assertThat(staysTrueFor(2_500, () -> scheduleCount(jobKey) == 1))
                .as("补跑不得随 tick 重复，实际=%d", scheduleCount(jobKey)).isTrue();
    }

    @Test
    void should_skip_missed_trigger_when_misfire_policy_ignore() throws Exception {
        // IGNORE：跳过错过的触发（ONCE 无下次 → 不触发）；对照任务证明内核存活、断言不空洞
        String ignoreKey = uniq("misfireignore");
        long ignoreId = createJobOk(
                onceBody(ignoreKey, GHOST_APP, "it.handler.ignore", Instant.now().minusSeconds(5),
                        "IGNORE"), ignoreKey);
        enableJob(ignoreId);

        String controlKey = uniq("control");
        long controlId = createJobOk(fixedRateBody(controlKey, GHOST_APP, "it.handler.ctl", 1000L), controlKey);
        enableJob(controlId);

        assertThat(await(6_000, () -> scheduleCount(controlKey) >= 1))
                .as("对照任务应正常触发（证明内核存活）").isTrue();

        assertThat(staysTrueFor(3_500, () -> scheduleCount(ignoreKey) == 0))
                .as("IGNORE 应跳过错过的触发不补跑（对照任务仍在触发），实际=%d",
                        scheduleCount(ignoreKey))
                .isTrue();
    }
}
