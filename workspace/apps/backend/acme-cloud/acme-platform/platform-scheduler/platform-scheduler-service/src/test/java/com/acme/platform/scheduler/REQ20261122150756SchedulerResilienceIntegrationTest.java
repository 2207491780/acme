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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-150756（JOB-4）调度韧性集成测试——退避重试/分片广播/告警接通知中心/超时归一/V3 索引重放
 * （Testcontainers PG 自起容器；装配照抄 {@code REQ20261121200724SchedulerV2IntegrationTest}
 * ＋{@code REQ20261121071002SchedulerCoreIntegrationTest} 先例：MockMvc HTTP 面 + JdbcTemplate 夹具
 * + 内嵌执行器桩（JDK HttpServer）+ 内嵌 platform-system 通知桩（记录 /internal/v1/notify/messages）；
 * 无 Docker 自动跳过——判定口径 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1/AC-2/AC-3/AC-4（scheduler 侧三类触发点+5 分钟去重）/AC-5/AC-6；
 * AC-4/AC-8 的 platform-system 端点腿见
 * {@code REQ20261122150756InternalNotifyApiIntegrationTest}（platform-system 测试域）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 分片定义入口=admin 创建/更新请求体扩展 shardable(boolean)+shardTotal(int)
 *     （契约③「shardTotal&gt;1 且定义 shardable」两旋钮并存；V1 已有 shard_total 列、JOB-3 JobView
 *     已有 shardTotal 展示面；V3 变更清单未提新增列——契约缺口已上报 TL，若裁决为 V3 独立列承载
 *     则仅本类 {@link #shardingBody} 夹具单点改 SQL 直改）；
 * A-2 单发兼容下发 body shardContext 按契约背景钉死现状语义 ShardContext(0, shardTotal)：
 *     index 恒 0、total 透传配置 shardTotal（r4 实测归因修正：原假定 total=1 与背景钉死事实冲突）；
 * A-3 告警外呼寻址=lb://platform-system（SimpleDiscoveryClient 静态实例表指向内嵌通知桩），
 *     与执行器下发寻址同机制；平台侧令牌校验（X-Acme-Token 有效性）归
 *     REQ20261122150756InternalNotifyApiIntegrationTest 的 AC-8 腿，本类只断言外呼在场与形状；
 * A-4 告警三类触发点在重试链中间失败也会各自触发（同 jobKey+reason 被去重窗收敛），
 *     故「重试耗尽」腿只断言至少一次外呼+形状，不断言精确次数；
 * A-5 退避间隔测量口径=RETRY 行自身 fire_time-created_at（DB 内求差，spawnRetry 的
 *     fire_time=失败时刻+间隔），±10% jitter 叠加 ±250ms 时钟斜差容忍带。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSchedulerApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-scheduler-it-secret-0123456789"
})
class REQ20261122150756SchedulerResilienceIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String STUB_APP = "acme-job4-executor-stub";

    /** 无执行器应用名（NO_EXECUTOR 快速终态腿；不在发现层实例表）。 */
    private static final String GHOST_APP = "acme-job4-ghost-app-no-executor";

    /** 告警外呼目标服务名（SimpleDiscoveryClient 静态实例指向内嵌通知桩，A-3）。 */
    private static final String NOTIFY_SYS_APP = "platform-system";

    private static final String JOBS_URL = "/admin/v1/scheduler/jobs";
    private static final String REGISTER_URL = "/internal/v1/scheduler/executors/register";
    private static final String NOTIFY_URL = "/internal/v1/notify/messages";

    /** 下发协议路径模板（JOB-1 契约面钉死）。 */
    private static final Pattern RUN_PATH =
            Pattern.compile("/internal/v1/job/executions/([^/]+)/run");

    private static final String ACCEPTED_BODY = "{\"accepted\":true}";

    /** AC-6 存量无损标记：V2 种子 4 行 jobKey（JOB-2 契约确认⑦）。 */
    private static final List<String> SEED_JOB_KEYS = List.of(
            "file-retention-cleanup",
            "login-log-retention-cleanup",
            "exception-log-retention-cleanup",
            "audit-retention-cleanup");

    /** 本类用例 jobKey 前缀（teardown 只清自有数据，种子保留）。 */
    private static final String IT_PREFIX = "it150756";

    /** 内嵌执行器桩（下发链路；JDK HttpServer 照 JOB-1 范本）。 */
    private static final ExecutorStub STUB = ExecutorStub.start();

    /** 内嵌通知桩（A-3：模拟 platform-system 承接 POST /internal/v1/notify/messages）。 */
    private static final NotifyStub NOTIFY = NotifyStub.start();

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
        // 执行器桩与通知桩均以 Spring Cloud SimpleDiscoveryClient 静态实例表承载（范本 A-2 同款）
        registry.add("spring.cloud.discovery.client.simple.instances." + STUB_APP + "[0].uri",
                () -> STUB.baseUri());
        registry.add("spring.cloud.discovery.client.simple.instances." + NOTIFY_SYS_APP + "[0].uri",
                () -> NOTIFY.baseUri());
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

    /** ShedLock 供应器（teardown 与内核同口径清缓存，范本同款）。 */
    @Autowired(required = false)
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        STUB.reset();
        NOTIFY.reset();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据：定义走 admin DELETE 契约面（内核同步卸载时间轮条目——范本口径）；
        // execution/registry 无内核耦合 SQL 物理清；通知桩/执行器桩复位；锁行复位写 UTC 墙钟
        STUB.reset();
        NOTIFY.reset();
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
        clearLockProviderCache();
    }

    /** 删行后须清 ShedLock 内存缓存（范本同款）。 */
    private void clearLockProviderCache() {
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    // ---------- AC-1：EXPONENTIAL 退避间隔按 multiplier 递增 ±10% jitter ----------

    @Test
    void should_retry_with_exponential_ascending_intervals_when_backoff_exponential()
            throws Exception {
        // GHOST_APP：NO_EXECUTOR 即时终态（无 10s 接单等待），重试链全速推进
        String jobKey = uniq("jobexp");
        ObjectNode body = fixedRateBody(jobKey, GHOST_APP, "it150756.handler.exp", 120_000L);
        ObjectNode retry = body.putObject("retryPolicy");
        retry.put("maxAttempts", 3);
        retry.put("initialIntervalMs", 1_000);
        retry.put("backoff", "EXPONENTIAL");
        retry.put("multiplier", 2);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        // 链路总时长 ≈ 1s+2s+4s+tick 粒度，45s 余量取齐 3 个 RETRY 行
        assertThat(await(45_000, () -> retryRowCount(jobKey) >= 3))
                .as("EXPONENTIAL 链应生成 RETRY attempt=1/2/3，实际=%d 行", retryRowCount(jobKey))
                .isTrue();

        List<RetryInterval> intervals = retryIntervals(jobKey);
        assertThat(intervals).as("应取到 attempt=1/2/3 三行").hasSize(3);
        assertWithinJitter(intervals.get(0).intervalMs(), 1_000, "第 1 次重试间隔（基准 1s）");
        assertWithinJitter(intervals.get(1).intervalMs(), 2_000, "第 2 次重试间隔（基准 2s=1s×2）");
        assertWithinJitter(intervals.get(2).intervalMs(), 4_000, "第 3 次重试间隔（基准 4s=2s×2）");
        assertThat(intervals.get(0).intervalMs()).as("间隔应严格递增")
                .isLessThan(intervals.get(1).intervalMs());
        assertThat(intervals.get(1).intervalMs()).as("间隔应严格递增")
                .isLessThan(intervals.get(2).intervalMs());
        assertThat(intervals.get(1).intervalMs() / intervals.get(0).intervalMs())
                .as("相邻间隔比应≈multiplier=2（±10% jitter+斜差带）").isBetween(1.3, 3.0);
        assertThat(intervals.get(2).intervalMs() / intervals.get(1).intervalMs())
                .as("相邻间隔比应≈multiplier=2（±10% jitter+斜差带）").isBetween(1.3, 3.0);
    }

    // ---------- AC-1：backoff 缺省=FIXED 现行为不变（旧两键形状兼容） ----------

    @Test
    void should_retry_with_constant_intervals_when_backoff_omitted_legacy_shape()
            throws Exception {
        // 旧两键形状（仅 maxAttempts+initialIntervalMs，无 backoff/multiplier/maxIntervalMs）
        String jobKey = uniq("jobfix");
        ObjectNode body = fixedRateBody(jobKey, GHOST_APP, "it150756.handler.fix", 120_000L);
        ObjectNode retry = body.putObject("retryPolicy");
        retry.put("maxAttempts", 3);
        retry.put("initialIntervalMs", 800);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        assertThat(await(30_000, () -> retryRowCount(jobKey) >= 3))
                .as("FIXED 链应生成 RETRY attempt=1/2/3，实际=%d 行", retryRowCount(jobKey))
                .isTrue();

        List<RetryInterval> intervals = retryIntervals(jobKey);
        assertThat(intervals).as("应取到 attempt=1/2/3 三行").hasSize(3);
        assertWithinJitter(intervals.get(0).intervalMs(), 800, "第 1 次重试间隔（恒定 800ms）");
        assertWithinJitter(intervals.get(1).intervalMs(), 800, "第 2 次重试间隔（恒定 800ms）");
        assertWithinJitter(intervals.get(2).intervalMs(), 800, "第 3 次重试间隔（恒定 800ms）");
        assertThat(intervals.get(1).intervalMs() / intervals.get(0).intervalMs())
                .as("FIXED 相邻间隔应基本恒定（现行为不变）").isBetween(0.7, 1.4);
        assertThat(intervals.get(2).intervalMs() / intervals.get(1).intervalMs())
                .as("FIXED 相邻间隔应基本恒定（现行为不变）").isBetween(0.7, 1.4);
    }

    // ---------- AC-1：EXPONENTIAL maxIntervalMs 封顶（确认记录② multiplier/maxIntervalMs 扩展） ----------

    @Test
    void should_cap_exponential_intervals_at_max_interval_ms_when_backoff_exponential_capped()
            throws Exception {
        // GHOST_APP：NO_EXECUTOR 即时终态（无 10s 接单等待），重试链全速推进
        String jobKey = uniq("jobcap");
        ObjectNode body = fixedRateBody(jobKey, GHOST_APP, "it150756.handler.cap", 120_000L);
        ObjectNode retry = body.putObject("retryPolicy");
        retry.put("maxAttempts", 3);
        retry.put("initialIntervalMs", 1_000);
        retry.put("backoff", "EXPONENTIAL");
        retry.put("multiplier", 4);
        retry.put("maxIntervalMs", 2_000);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        // 链路总时长 ≈ 1s+2s+2s+tick 粒度，45s 余量取齐 3 个 RETRY 行
        assertThat(await(45_000, () -> retryRowCount(jobKey) >= 3))
                .as("封顶链应生成 RETRY attempt=1/2/3，实际=%d 行", retryRowCount(jobKey)).isTrue();

        List<RetryInterval> intervals = retryIntervals(jobKey);
        assertThat(intervals).as("应取到 attempt=1/2/3 三行").hasSize(3);
        assertWithinJitter(intervals.get(0).intervalMs(), 1_000, "第 1 次重试间隔（基准 1s，未触顶）");
        // 未封顶理论值 4s（≥3.4s 带）/8s——封顶后两段都落在 2s 带（±10% jitter+斜差容忍）
        assertWithinJitter(intervals.get(1).intervalMs(), 2_000,
                "第 2 次重试间隔（理论 4s 应被 maxIntervalMs=2s 封顶）");
        assertWithinJitter(intervals.get(2).intervalMs(), 2_000,
                "第 3 次重试间隔（理论 8s 应被 maxIntervalMs=2s 封顶）");
        assertThat(intervals.get(1).intervalMs())
                .as("封顶证据：第 2 次间隔不得进入未封顶 4s 带（≥3.4s）").isLessThan(3_000);
        assertThat(intervals.get(2).intervalMs())
                .as("封顶证据：第 3 次间隔不得进入未封顶 8s 带").isLessThan(3_000);
    }

    // ---------- AC-2：shardable 定义 shardTotal=3 → 3 条 execution 落 shard_index + 串行门 ----------

    @Test
    void should_fan_out_one_execution_per_shard_index_and_gate_next_cycle_when_definition_shardable()
            throws Exception {
        registerExecutor(uniq("inst-shard"));
        String jobKey = uniq("jobshard");
        long jobId = createJobOk(shardingBody(jobKey, STUB_APP, "it150756.handler.shard",
                1_000L, true, 3), jobKey);
        enableJob(jobId);

        // ① 3 条 SCHEDULE execution（shard_index=0/1/2 落库）
        assertThat(await(15_000, () -> scheduleCount(jobKey) == 3))
                .as("扇出应恰生成 3 条 SCHEDULE execution，实际=%d", scheduleCount(jobKey)).isTrue();
        assertThat(shardIndices(jobKey)).as("shard_index 应为 0/1/2").containsExactlyInAnyOrder(0, 1, 2);

        // ② 各片独立下发：3 次下发、body shardContext{index,total=3} 与落库片号一一对应
        assertThat(await(10_000, () -> dispatchesFor(jobKey).size() >= 3))
                .as("应向执行器下发 3 次（每片一次），实际=%d 次", dispatchesFor(jobKey).size()).isTrue();
        Map<Long, Integer> executionIdToShard = dispatchShardMap(jobKey);
        assertThat(executionIdToShard).as("3 次下发应对应 3 个 execution").hasSize(3);
        assertThat(executionIdToShard.values())
                .as("下发 body shardContext.index 应覆盖 0/1/2").containsExactlyInAnyOrder(0, 1, 2);

        // ③ 各片独立上报：片 1/2 先 SUCCESS、片 0 保持未终态（RUNNING）
        long shard0 = executionIdForShard(executionIdToShard, 0);
        long shard1 = executionIdForShard(executionIdToShard, 1);
        long shard2 = executionIdForShard(executionIdToShard, 2);
        pushToRunning(shard0);
        finishSuccess(shard1);
        finishSuccess(shard2);
        assertThat(await(6_000, () -> "SUCCESS".equals(stateOf(shard1))))
                .as("片 1 应独立推进 SUCCESS，实际=%s", stateOf(shard1)).isTrue();
        assertThat(await(6_000, () -> "SUCCESS".equals(stateOf(shard2))))
                .as("片 2 应独立推进 SUCCESS，实际=%s", stateOf(shard2)).isTrue();
        assertThat(stateOf(shard0)).as("片 0 应保持未终态").isIn("PENDING", "DISPATCHED", "RUNNING");

        // ④ 串行门：任一片（片 0）未终态 → 跨多个 tick 跳过新周期（不新增 SCHEDULE 行）
        boolean gated = staysTrueFor(3_000, () -> scheduleCount(jobKey) == 3);
        assertThat(gated).as("任一片未终态期间不得触发新周期（串行门），实际=%d 行",
                scheduleCount(jobKey)).isTrue();

        // ⑤ 片 0 终态 → 下个 fire_time 新周期重新扇出 3 条（新 fire_time、片号 0/1/2）
        ok(reportResult(shard0, "SUCCESS", null, null), "片 0 收尾 SUCCESS 应成功");
        assertThat(await(15_000, () -> scheduleCount(jobKey) == 6))
                .as("全片终态后应触发新周期再扇出 3 条，实际=%d", scheduleCount(jobKey)).isTrue();
        List<Long> fireTimes = scheduleFireTimes(jobKey);
        assertThat(fireTimes).as("应恰两个周期 fire_time").hasSize(2);
        assertThat(fireTimes.get(1)).as("新周期 fire_time 应晚于首周期").isGreaterThan(fireTimes.get(0));
        Set<Integer> secondCycle = secondCycleShardIndices(jobKey);
        assertThat(secondCycle).as("新周期 shard_index 应仍为 0/1/2").containsExactlyInAnyOrder(0, 1, 2);
    }

    // ---------- AC-3：shardTotal=3 但 shardable=false → 单发兼容（shard_index=0） ----------

    @Test
    void should_keep_single_dispatch_with_shard_index_zero_when_definition_not_shardable()
            throws Exception {
        registerExecutor(uniq("inst-single"));
        String jobKey = uniq("jobsingle");
        long jobId = createJobOk(shardingBody(jobKey, STUB_APP, "it150756.handler.single",
                1_000L, false, 3), jobKey);
        enableJob(jobId);

        // 单发：恰 1 条 SCHEDULE execution、shard_index=0、恰 1 次下发（A-2：total=1 兼容形态）
        assertThat(await(15_000, () -> scheduleCount(jobKey) >= 1))
                .as("应触发生成 SCHEDULE execution").isTrue();
        assertThat(scheduleCount(jobKey)).as("shardable=false 应保持单发（恰 1 条）").isEqualTo(1L);
        assertThat(shardIndices(jobKey)).as("单发 shard_index 应为 0").containsExactly(0);
        List<RecordedRequest> dispatches = await(10_000, () -> dispatchesFor(jobKey).size() >= 1)
                ? dispatchesFor(jobKey) : List.of();
        assertThat(dispatches.size()).as("单发应恰 1 次下发，实际=%d", dispatches.size()).isEqualTo(1);
        JsonNode dispatchBody = JSON.readTree(dispatches.get(0).body());
        assertThat(dispatchBody.path("shardContext").path("index").asInt())
                .as("单发下发 shardContext.index").isZero();
        assertThat(dispatchBody.path("shardContext").path("total").asInt())
                .as("单发下发 shardContext.total（契约背景钉死现状语义 ShardContext(0,shardTotal)，"
                        + "单发兼容=total 透传配置值）").isEqualTo(3);

        // 接单未上报（未终态）→ 串行门保持单发不重复触发
        boolean single = staysTrueFor(2_500, () -> scheduleCount(jobKey) == 1);
        assertThat(single).as("单发语义下不得重复触发新周期，实际=%d 行", scheduleCount(jobKey)).isTrue();
    }

    // ---------- AC-3：shardable=true 但 shardTotal=1 → 单发兼容（裁决③ 完整矩阵：shardTotal=1 不扇出） ----------

    @Test
    void should_keep_single_dispatch_when_shardable_true_but_shard_total_one()
            throws Exception {
        registerExecutor(uniq("inst-shardone"));
        String jobKey = uniq("jobshardone");
        long jobId = createJobOk(shardingBody(jobKey, STUB_APP, "it150756.handler.shardone",
                1_000L, true, 1), jobKey);
        enableJob(jobId);

        // 单分片：不扇出，恰 1 条 SCHEDULE execution、shard_index 恒 0（契约③「单分片行为不变」）
        assertThat(await(15_000, () -> scheduleCount(jobKey) >= 1))
                .as("应触发生成 SCHEDULE execution").isTrue();
        assertThat(scheduleCount(jobKey)).as("shardable=true 但 shardTotal=1 应保持单发（恰 1 条）")
                .isEqualTo(1L);
        assertThat(shardIndices(jobKey)).as("单分片 shard_index 应恒 0").containsExactly(0);
        List<RecordedRequest> dispatches = await(10_000, () -> dispatchesFor(jobKey).size() >= 1)
                ? dispatchesFor(jobKey) : List.of();
        assertThat(dispatches.size()).as("单分片应恰 1 次下发，实际=%d", dispatches.size()).isEqualTo(1);
        JsonNode dispatchBody = JSON.readTree(dispatches.get(0).body());
        assertThat(dispatchBody.path("shardContext").path("index").asInt())
                .as("单分片下发 shardContext.index").isZero();
        assertThat(dispatchBody.path("shardContext").path("total").asInt())
                .as("单分片下发 shardContext.total=1（A-2 兼容形态）").isEqualTo(1);

        // 接单未上报（未终态）→ 串行门保持单发不重复触发
        boolean single = staysTrueFor(2_500, () -> scheduleCount(jobKey) == 1);
        assertThat(single).as("单分片语义下不得重复触发新周期，实际=%d 行",
                scheduleCount(jobKey)).isTrue();
    }

    // ---------- AC-4（腿 1）：重试链耗尽 → 外呼 internal notify ----------

    @Test
    void should_notify_internal_system_when_retry_chain_exhausted() throws Exception {
        // 桩恒 500（下发失败）→ attempt0 + RETRY attempt1 走完 → 耗尽终态（JOB-1 耗尽腿同款）
        STUB.scenario(ExecutorStub.Scenario.REJECT_500);
        String jobKey = uniq("jobexhaust");
        ObjectNode body = onceBody(jobKey, STUB_APP, "it150756.handler.exhaust",
                Instant.now().plusSeconds(1), "FIRE_ONCE_NOW");
        ObjectNode retry = body.putObject("retryPolicy");
        retry.put("maxAttempts", 1);
        retry.put("initialIntervalMs", 200);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        boolean settled = await(20_000, () -> {
            Long total = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_execution where job_key = ?", Long.class, jobKey);
            Long failed = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_execution where job_key = ? and state = 'FAILED'",
                    Long.class, jobKey);
            return total != null && failed != null && total >= 2 && total.equals(failed);
        });
        assertThat(settled).as("重试链耗尽后全部 execution 应终态 FAILED").isTrue();

        // 耗尽后应外呼 POST /internal/v1/notify/messages（A-4：只断言≥1+形状）
        assertThat(await(8_000, () -> !notifyRequestsFor(jobKey).isEmpty()))
                .as("重试耗尽应外呼内部告警端点，实际=%d 次", notifyRequestsFor(jobKey).size()).isTrue();
        for (RecordedRequest call : notifyRequestsFor(jobKey)) {
            assertNotifyShape(call, jobKey);
        }
    }

    // ---------- AC-4（腿 3）：巡检 TIMEOUT → 外呼 internal notify ----------

    @Test
    void should_notify_internal_system_when_patrol_marks_timeout() throws Exception {
        registerExecutor(uniq("inst-notify-to"));
        STUB.scenario(ExecutorStub.Scenario.ACCEPT_OK);
        String jobKey = uniq("jobpatrol");
        ObjectNode body = fixedRateBody(jobKey, STUB_APP, "it150756.handler.patrol", 1_000L);
        body.put("timeoutMs", 1_000L);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应发生下发").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        pushToRunning(executionId);

        assertThat(await(15_000, () -> "TIMEOUT".equals(stateOf(executionId))))
                .as("RUNNING 超时应被巡检标记 TIMEOUT，实际=%s", stateOf(executionId)).isTrue();
        assertThat(await(8_000, () -> !notifyRequestsFor(jobKey).isEmpty()))
                .as("巡检 TIMEOUT 应外呼内部告警端点，实际=%d 次", notifyRequestsFor(jobKey).size()).isTrue();
        for (RecordedRequest call : notifyRequestsFor(jobKey)) {
            assertNotifyShape(call, jobKey);
        }
    }

    // ---------- AC-4（腿 2 补全）：孤儿 DISPATCHED → 巡检 DISPATCH_TIMEOUT → 告警+重试链 ----------
    // TL 裁决②（2026-11-22）：JOB-1 契约⑤钉死「受理即 RUNNING」——真实下发受理不回调 → TIMEOUT
    // 属正确行为（AC-5 既有腿已绿）；孤儿分支须 SQL 注入造 stale PENDING/DISPATCHED execution
    // （fire_time 超 2×dispatch 窗口）命中。

    @Test
    void should_notify_and_spawn_retry_when_patrol_marks_orphan_dispatch_timeout()
            throws Exception {
        // ONCE 未来触发：观察窗内无真实下发噪音；GHOST_APP 使 RETRY 下发失败不影响断言（只验 RETRY 行）
        String jobKey = uniq("joborphan");
        ObjectNode body = onceBody(jobKey, GHOST_APP, "it150756.handler.orphanto",
                Instant.now().plusSeconds(600), "FIRE_ONCE_NOW");
        ObjectNode retry = body.putObject("retryPolicy");
        retry.put("maxAttempts", 1);
        retry.put("initialIntervalMs", 500);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        // SQL 注入 stale 孤儿 DISPATCHED execution（fire_time/created_at 早于 2×dispatch 窗口）
        long staleId = 9_150_756_000_002L;
        jdbcTemplate.update("""
                        insert into sch_job_execution (id, definition_id, job_key, trigger_type, fire_time,
                                                       state, attempt, shard_index, error,
                                                       created_at, created_by, updated_at, updated_by,
                                                       version, deleted)
                        values (?, ?, ?, 'SCHEDULE', now() - interval '10 minutes', 'DISPATCHED', 0, 0, null,
                                now() - interval '10 minutes', 'req150756-it',
                                now() - interval '10 minutes', 'req150756-it', 0, false)
                        """,
                staleId, jobId, jobKey);

        // 巡检孤儿兜底：stale PENDING/DISPATCHED → dispatch 超时终态化（背景钉死「→DISPATCH_TIMEOUT+重试链」）。
        // 终态形态宽容双口径：state=DISPATCH_TIMEOUT 独立终态，或 state=FAILED+error 携带
        // DISPATCH_TIMEOUT（与 NO_EXECUTOR 腿实证形态 state=FAILED+error 同构，pitfalls 13/14 宽出口径）。
        assertThat(await(20_000, () -> executionStateAndError(staleId).contains("DISPATCH_TIMEOUT")))
                .as("孤儿 PENDING/DISPATCHED 应被巡检按 dispatch 超时终态化（state=DISPATCH_TIMEOUT "
                        + "或 state=FAILED+error 含 DISPATCH_TIMEOUT），实际=%s",
                        executionStateAndError(staleId)).isTrue();

        // 契约④：DISPATCH_TIMEOUT 属 dispatch 三态终态失败 → 外呼内部告警（≥1+形状）
        assertThat(await(8_000, () -> !notifyRequestsFor(jobKey).isEmpty()))
                .as("DISPATCH_TIMEOUT 应外呼内部告警端点，实际=%d 次",
                        notifyRequestsFor(jobKey).size()).isTrue();
        for (RecordedRequest call : notifyRequestsFor(jobKey)) {
            assertNotifyShape(call, jobKey);
        }

        // 背景钉死：孤儿 PENDING/DISPATCHED→DISPATCH_TIMEOUT+重试链（retryPolicy 非空 → 派生 RETRY 行）
        assertThat(await(10_000, () -> retryRowCount(jobKey) >= 1))
                .as("DISPATCH_TIMEOUT 应回派重试链（RETRY attempt=1），实际=%d 行",
                        retryRowCount(jobKey)).isTrue();
    }

    // ---------- AC-4（腿 2+去重）：dispatch 终态失败重复发生同 jobKey+reason → 5 分钟窗恰 1 次 ----------

    @Test
    void should_deduplicate_alarm_within_five_minute_window_when_same_job_repeatedly_fails_same_reason()
            throws Exception {
        // GHOST_APP 无重试：每 tick NO_EXECUTOR 即时终态（同 jobKey+同 reason 重复触发告警）
        String jobKey = uniq("jobdedup");
        ObjectNode body = fixedRateBody(jobKey, GHOST_APP, "it150756.handler.dedup", 800L);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        // ≥3 个真实失败事件（均 NO_EXECUTOR 终态）
        assertThat(await(15_000, () -> failedNoExecutorCount(jobKey) >= 3))
                .as("应累积 ≥3 次 NO_EXECUTOR 终态失败，实际=%d", failedNoExecutorCount(jobKey)).isTrue();

        // 去重窗（5 分钟）内同 jobKey+reason 只外呼 1 次，观察窗内稳定
        assertThat(await(8_000, () -> notifyRequestsFor(jobKey).size() == 1))
                .as("去重窗内同 jobKey+reason 应恰外呼 1 次，实际=%d 次",
                        notifyRequestsFor(jobKey).size()).isTrue();
        boolean stable = staysTrueFor(2_500, () -> notifyRequestsFor(jobKey).size() == 1
                && failedNoExecutorCount(jobKey) >= 3);
        assertThat(stable).as("重复失败不得突破去重窗（仍恰 1 次），实际=%d 次",
                notifyRequestsFor(jobKey).size()).isTrue();
        for (RecordedRequest call : notifyRequestsFor(jobKey)) {
            assertNotifyShape(call, jobKey);
        }
    }

    // ---------- AC-5：SDK watchdog 先报 TIMEOUT → 回调 CAS 接受；终态幂等不重复推进 ----------

    @Test
    void should_accept_timeout_callback_and_stay_terminal_idempotent_when_watchdog_reports_first()
            throws Exception {
        registerExecutor(uniq("inst-cbto"));
        STUB.scenario(ExecutorStub.Scenario.ACCEPT_OK);
        // timeoutMs=60s：巡检不在观察窗内触发（TIMEOUT 由回调先到；先到先得口径）
        String jobKey = uniq("jobcbtimeout");
        ObjectNode body = fixedRateBody(jobKey, STUB_APP, "it150756.handler.cbto", 60_000L);
        body.put("timeoutMs", 60_000L);
        long jobId = createJobOk(body, jobKey);
        enableJob(jobId);

        RecordedRequest dispatch = awaitDispatch(jobKey, 12_000);
        assertThat(dispatch).as("应发生下发").isNotNull();
        long executionId = executionIdOf(dispatch.path());
        pushToRunning(executionId);

        // SDK watchdog 上报 TIMEOUT → 回调通道应接受（背景钉死：原仅收 RUNNING/SUCCESS/FAILED）
        ok(reportResult(executionId, "TIMEOUT", "sdk-watchdog-interrupt", null),
                "RUNNING 态回调 TIMEOUT 应被接受");
        assertThat(await(6_000, () -> "TIMEOUT".equals(stateOf(executionId))))
                .as("回调 TIMEOUT 应落 TIMEOUT 终态，实际=%s", stateOf(executionId)).isTrue();

        // 终态幂等：重复 TIMEOUT/终态改写 → CAS 拒绝且状态不变
        JsonNode repeated = reportResult(executionId, "TIMEOUT", "sdk-watchdog-again", null);
        bizFail(repeated, "终态后重复 TIMEOUT 应拒绝");
        JsonNode lateSuccess = reportResult(executionId, "SUCCESS", null, null);
        bizFail(lateSuccess, "终态后 SUCCESS 应拒绝");
        assertThat(stateOf(executionId)).as("终态不得被改写").isEqualTo("TIMEOUT");

        // 巡检后到不重复推进：观察窗内状态稳定、不因巡检/回调派生新 RETRY 行
        boolean stable = staysTrueFor(2_500, () -> "TIMEOUT".equals(stateOf(executionId))
                && retryRowCount(jobKey) == 0);
        assertThat(stable).as("TIMEOUT 终态后巡检/回调不得重复推进或派生重试，实际 state=%s RETRY=%d 行",
                stateOf(executionId), retryRowCount(jobKey)).isTrue();
    }

    // ---------- AC-6：V3 迁移 uk 重建幂等 + 存量无损 ----------

    @Test
    void should_rebuild_shard_unique_index_and_preserve_rows_when_v3_migration_replayed()
            throws Exception {
        // ① Flyway V3 已执行成功
        Long v3Rows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '3' and success", Long.class);
        assertThat(v3Rows).as("scheduler V3 迁移应已执行成功").isGreaterThan(0L);

        // ② uk(def_id,fire_time,shard_index) WHERE trigger_type='SCHEDULE' AND attempt=0（契约③）
        assertShardUniqueIndexPresent();

        // ③ 存量在位：V2 种子 4 行 + 造一条存量形态执行行（SCHEDULE attempt=0）+ 裁决⑥加列默认
        for (String seedKey : SEED_JOB_KEYS) {
            Long seedCount = jdbcTemplate.queryForObject(
                    "select count(*) from sch_job_definition where job_key = ? and deleted = false",
                    Long.class, seedKey);
            assertThat(seedCount).as("存量种子 %s 应无损在位", seedKey).isEqualTo(1L);
        }
        Long legacyShardableFalse = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key in (?,?,?,?) "
                        + "and deleted = false and shardable = false",
                Long.class, SEED_JOB_KEYS.get(0), SEED_JOB_KEYS.get(1),
                SEED_JOB_KEYS.get(2), SEED_JOB_KEYS.get(3));
        assertThat(legacyShardableFalse)
                .as("V3 加列后存量定义 shardable 应默认 false（裁决⑥单发兼容无损）").isEqualTo(4L);
        long legacyId = 9_150_756_000_001L;
        jdbcTemplate.update("""
                        insert into sch_job_execution (id, definition_id, job_key, trigger_type, fire_time,
                                                       state, attempt, shard_index, error,
                                                       created_at, created_by, updated_at, updated_by,
                                                       version, deleted)
                        values (?, ?, ?, 'SCHEDULE', now(), 'FAILED', 0, 0, 'legacy-row',
                                now(), 'req150756-it', now(), 'req150756-it', 0, false)
                        """,
                legacyId, 9_150_756_000_000L, IT_PREFIX + "-legacy-row");
        Map<String, Object> legacyBefore = jdbcTemplate.queryForMap(
                "select job_key, state, attempt, shard_index, error from sch_job_execution where id = ?",
                legacyId);

        // ④ 迁移重放（范本 A-3 口径：V3__*.sql 文件名无关 glob、原样二次执行；不抛=幂等）
        Path v3File = v3MigrationFile();
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(v3File.toFile()));
        }

        // ⑤ 重放后：索引仍在位（重建成功）、存量行/种子无损
        assertShardUniqueIndexPresent();
        Map<String, Object> legacyAfter = jdbcTemplate.queryForMap(
                "select job_key, state, attempt, shard_index, error from sch_job_execution where id = ?",
                legacyId);
        assertThat(legacyAfter).as("重放后存量执行行字段不得丢失或被改写").isEqualTo(legacyBefore);
        Long seedCountAfter = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key in (?,?,?,?) and deleted = false",
                Long.class, SEED_JOB_KEYS.get(0), SEED_JOB_KEYS.get(1),
                SEED_JOB_KEYS.get(2), SEED_JOB_KEYS.get(3));
        assertThat(seedCountAfter).as("重放后 V2 种子 4 行应无损在位").isEqualTo(4L);
    }

    /** uk(def_id,fire_time,shard_index) 部分唯一索引 catalog 断言（范本 pg_indexes 口径）。 */
    private void assertShardUniqueIndexPresent() {
        List<String> indexDefs = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema() "
                        + "and tablename = 'sch_job_execution'", String.class);
        assertThat(indexDefs).as("sch_job_execution 应有含 shard_index 的部分唯一索引（V3 契约③）")
                .anySatisfy(def -> {
                    String normalized = def.toLowerCase().replace(" ", "");
                    assertThat(normalized)
                            .contains("unique")
                            .contains("definition_id")
                            .contains("fire_time")
                            .contains("shard_index")
                            .contains("where")
                            .contains("'schedule'")
                            .contains("attempt=0");
                });
    }

    // ---------- 夹具装配（照抄范本；A-1 分片字段单点） ----------

    private String uniq(String prefix) {
        return IT_PREFIX + SEQ.incrementAndGet() + prefix;
    }

    /** 任务定义请求体缺省骨架（不带 retryPolicy：契约「空=不重试」缺省形态）。 */
    private ObjectNode baseJobBody(String jobKey, String appName, String handler) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jobKey", jobKey);
        node.put("appName", appName);
        node.put("handler", handler);
        node.put("timeoutMs", 60_000L);
        node.put("misfirePolicy", "FIRE_ONCE_NOW");
        return node;
    }

    /** FIXED_RATE 任务（无 retryPolicy=不重试）。 */
    private ObjectNode fixedRateBody(String jobKey, String appName, String handler, long fixedRateMs) {
        ObjectNode node = baseJobBody(jobKey, appName, handler);
        node.put("triggerType", "FIXED_RATE");
        node.put("fixedRateMs", fixedRateMs);
        return node;
    }

    /** 分片定义请求体（A-1 假定单点：shardable+shardTotal 随创建体扩展；裁决变更只改此处）。 */
    private ObjectNode shardingBody(String jobKey, String appName, String handler,
                                    long fixedRateMs, boolean shardable, int shardTotal) {
        ObjectNode node = fixedRateBody(jobKey, appName, handler, fixedRateMs);
        node.put("shardable", shardable);
        node.put("shardTotal", shardTotal);
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

    private void registerExecutor(String instanceId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("appName", STUB_APP);
        body.put("instanceId", instanceId);
        body.put("host", "127.0.0.1");
        body.put("port", STUB.port());
        var handlers = body.putArray("handlers");
        handlers.add("it150756.handler");
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

    /** 业务失败信封断言（pitfalls 口径：Result.code≠0，勿断言 4xx）。 */
    private JsonNode bizFail(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + " 应业务失败：%s", body).isFalse();
        assertThat(body.path("code").asText()).as(desc + " code 应≠0：%s", body).isNotEqualTo("0");
        return body;
    }

    /** /result 回调（JOB-2 范本口径，扩展 costMs 位形参）。 */
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

    /** 片执行收尾：DISPATCHED 宽容分支补 RUNNING 回调后推 SUCCESS（范本 pushToRunning 同款）。 */
    private void finishSuccess(long executionId) throws Exception {
        String state = null;
        long deadline = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < deadline) {
            state = stateOf(executionId);
            if ("DISPATCHED".equals(state) || "RUNNING".equals(state)) {
                break;
            }
            sleepQuietly(100);
        }
        if ("DISPATCHED".equals(state)) {
            ok(reportResult(executionId, "RUNNING", null, null), "补发 RUNNING 回调应成功");
        }
        ok(reportResult(executionId, "SUCCESS", null, 100L), "片执行 SUCCESS 回调应成功");
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

    /** SCHEDULE attempt=0 的 execution 行数（触发/串行门断言口径，范本同款）。 */
    private long scheduleCount(String jobKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Long.class, jobKey);
        return count == null ? 0L : count;
    }

    private long retryRowCount(String jobKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? and trigger_type = 'RETRY'",
                Long.class, jobKey);
        return count == null ? 0L : count;
    }

    private String stateOf(long executionId) {
        List<String> states = jdbcTemplate.queryForList(
                "select state from sch_job_execution where id = ?", String.class, executionId);
        return states.isEmpty() ? null : states.get(0);
    }

    /** state|error 拼合读取（孤儿终态双形态断言与归因消息用）。 */
    private String executionStateAndError(long executionId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select coalesce(state, '') || '|' || coalesce(error, '') from sch_job_execution "
                        + "where id = ?", String.class, executionId);
        return rows.isEmpty() ? "(row-missing)" : rows.get(0);
    }

    /** shard_index 去重集合（SCHEDULE attempt=0 行）。 */
    private Set<Integer> shardIndices(String jobKey) {
        Set<Integer> indices = new HashSet<>();
        jdbcTemplate.queryForList(
                "select distinct shard_index from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0", Integer.class, jobKey)
                .forEach(indices::add);
        return indices;
    }

    /** 去重后的周期 fire_time 升序（毫秒），同周期多片共享同一 fire_time。 */
    private List<Long> scheduleFireTimes(String jobKey) {
        List<Long> distinct = new ArrayList<>();
        List<java.sql.Timestamp> stamps = jdbcTemplate.queryForList(
                "select distinct fire_time from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0 order by fire_time",
                java.sql.Timestamp.class, jobKey);
        for (java.sql.Timestamp stamp : stamps) {
            distinct.add(stamp.getTime());
        }
        return distinct;
    }

    /** 第二周期（晚于首周期 fire_time）的片号集合（区间口径：PG fire_time 微秒精度，等值回查会因
     *  getTime() 毫秒截断落空，pitfalls ⑥按库中真实状态推演）。 */
    private Set<Integer> secondCycleShardIndices(String jobKey) {
        List<Long> fireTimes = scheduleFireTimes(jobKey);
        assertThat(fireTimes).as("第二周期应已生成").hasSizeGreaterThanOrEqualTo(2);
        Set<Integer> indices = new HashSet<>();
        jdbcTemplate.queryForList(
                "select distinct shard_index from sch_job_execution where job_key = ? "
                        + "and trigger_type = 'SCHEDULE' and attempt = 0 and fire_time > ?",
                Integer.class, jobKey, new java.sql.Timestamp(fireTimes.get(0)))
                .forEach(indices::add);
        return indices;
    }

    /** 终态为 FAILED 且 error 含 NO_EXECUTOR 的 execution 行数（去重用例失败事件口径）。 */
    private long failedNoExecutorCount(String jobKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_execution where job_key = ? and state = 'FAILED' "
                        + "and error like '%NO_EXECUTOR%'", Long.class, jobKey);
        return count == null ? 0L : count;
    }

    /** RETRY 行退避间隔（A-5 口径：fire_time-created_at DB 内求差，毫秒）。 */
    private List<RetryInterval> retryIntervals(String jobKey) {
        List<Double> millis = jdbcTemplate.queryForList(
                "select extract(epoch from (fire_time - created_at)) * 1000 from sch_job_execution "
                        + "where job_key = ? and trigger_type = 'RETRY' order by attempt",
                Double.class, jobKey);
        List<RetryInterval> intervals = new ArrayList<>();
        for (Double ms : millis) {
            intervals.add(new RetryInterval(ms == null ? 0 : ms));
        }
        return intervals;
    }

    /** ±10% jitter + ±250ms 时钟斜差容忍带（A-5）。 */
    private static void assertWithinJitter(double actualMs, double baseMs, String desc) {
        assertThat(actualMs)
                .as("%s：实际=%.0fms 基准=%.0fms（±10%% jitter 带）", desc, actualMs, baseMs)
                .isBetween(baseMs * 0.85 - 250, baseMs * 1.15 + 250);
    }

    private record RetryInterval(double intervalMs) {
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

    /** 观察窗：windowMs 内条件持续为真才通过（负例/稳定性断言口径，范本同款）。 */
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

    /** 指向桩执行器的下发请求（body 含 jobKey）。 */
    private List<RecordedRequest> dispatchesFor(String jobKey) {
        List<RecordedRequest> dispatches = new ArrayList<>();
        for (RecordedRequest request : STUB.requests()) {
            if (request.path().startsWith("/internal/v1/job/executions/")
                    && request.body().contains(jobKey)) {
                dispatches.add(request);
            }
        }
        return dispatches;
    }

    /** executionId → shardContext.index 映射（下发协议面）。 */
    private Map<Long, Integer> dispatchShardMap(String jobKey) throws IOException {
        Map<Long, Integer> mapping = new LinkedHashMap<>();
        for (RecordedRequest request : dispatchesFor(jobKey)) {
            long executionId = executionIdOf(request.path());
            JsonNode body = JSON.readTree(request.body());
            mapping.put(executionId, body.path("shardContext").path("index").asInt(-1));
        }
        return mapping;
    }

    private long executionIdForShard(Map<Long, Integer> executionIdToShard, int shardIndex) {
        return executionIdToShard.entrySet().stream()
                .filter(entry -> entry.getValue() == shardIndex)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 shard_index=" + shardIndex + " 的下发"));
    }

    /** 把执行推进到 RUNNING（范本 A-5 宽容分支同款）。 */
    private void pushToRunning(long executionId) throws Exception {
        String state = null;
        long deadline = System.currentTimeMillis() + 6_000;
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

    /** 告警外呼形状断言（契约④钉死面：POST /internal/v1/notify/messages + X-Acme-Token
     *  + body={sender,title,content?}，sender=platform-scheduler，内容可追溯 jobKey）。 */
    private void assertNotifyShape(RecordedRequest call, String jobKey) {
        assertThat(call.path()).as("告警外呼路径应照契约④").isEqualTo(NOTIFY_URL);
        assertThat(call.token()).as("告警外呼应携带 X-Acme-Token 内部令牌").isNotBlank();
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.path("sender").asText())
                .as("告警 body sender 应为服务主体 platform-scheduler").isEqualTo("platform-scheduler");
        assertThat(body.path("title").asText()).as("告警 body title 应非空").isNotBlank();
        assertThat(call.body()).as("告警内容应可追溯 jobKey").contains(jobKey);
    }

    /** 指向通知桩的外呼请求（body 含 jobKey）。 */
    private List<RecordedRequest> notifyRequestsFor(String jobKey) {
        List<RecordedRequest> calls = new ArrayList<>();
        for (RecordedRequest request : NOTIFY.requests()) {
            if (request.body().contains(jobKey)) {
                calls.add(request);
            }
        }
        return calls;
    }

    // ---------- V3 迁移文件定位（范本 A-3：文件名无关 glob） ----------

    private static Path v3MigrationFile() throws IOException {
        Path dir = acmeCloudRoot().resolve(
                "acme-platform/platform-scheduler/platform-scheduler-service/src/main/resources/db/migration/scheduler");
        assertThat(Files.isDirectory(dir)).as("scheduler 迁移目录应存在：%s", dir).isTrue();
        try (var files = Files.list(dir)) {
            Optional<Path> v3 = files
                    .filter(p -> p.getFileName().toString().startsWith("V3__"))
                    .findFirst();
            assertThat(v3).as("scheduler V3 迁移文件应已交付（未交付则本用例红=实现未交付）").isPresent();
            return v3.get();
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

    // ---------- 内嵌执行器桩（JDK HttpServer，JOB-1/JOB-2 范本精简） ----------

    record RecordedRequest(String method, String path, String body, String token, Instant receivedAt) {
    }

    static final class ExecutorStub {

        enum Scenario { ACCEPT_OK, REJECT_500 }

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private volatile Scenario scenario = Scenario.ACCEPT_OK;

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

        void scenario(Scenario newScenario) {
            this.scenario = newScenario;
        }

        void reset() {
            requests.clear();
            this.scenario = Scenario.ACCEPT_OK;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            RecordedRequest recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Acme-Token"),
                    Instant.now());
            requests.add(recorded);
            byte[] bytes;
            if (scenario == Scenario.REJECT_500) {
                bytes = "{\"error\":\"stub-reject-500\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
            } else {
                bytes = ACCEPTED_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
            }
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    // ---------- 内嵌通知桩（A-3：模拟 platform-system 承接内部告警） ----------

    static final class NotifyStub {

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private NotifyStub(HttpServer server) {
            this.server = server;
        }

        static NotifyStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                NotifyStub stub = new NotifyStub(server);
                server.createContext("/", stub::handle);
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌通知桩启动失败", ex);
            }
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
                    exchange.getRequestHeaders().getFirst("X-Acme-Token"),
                    Instant.now());
            requests.add(recorded);
            byte[] bytes = "{\"code\":\"0\",\"success\":true,\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
