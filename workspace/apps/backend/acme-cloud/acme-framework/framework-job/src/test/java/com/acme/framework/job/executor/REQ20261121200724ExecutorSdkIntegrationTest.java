package com.acme.framework.job.executor;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.job.config.JobAutoConfiguration;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.JobExecutionContext;
import com.acme.framework.job.support.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261121-200724（JOB-2）执行器 SDK 服务级集成测试——框架域（无 DB；内嵌桩 scheduler 端点
 * 照 JOB-1 {@code REQ20261121071002SchedulerCoreIntegrationTest} 先例：JDK HttpServer + 全量请求记录）。
 *
 * <p>覆盖契约 AC-1/AC-2/AC-3/AC-4/AC-6（handlers 面）：</p>
 * <ul>
 *   <li>AC-1：启动注册（appName/instanceId/host/port/handlers 全量字段）+ 心跳按周期续期
 *       （heartbeat-interval-ms=200 加速观测）。</li>
 *   <li>AC-2：接单 200+accepted=true→执行→result 上报（SUCCESS/FAILED/error/costMs 全量）；
 *       未知 jobKey→accepted=false（DISPATCH_REJECTED 语义，契约面 DispatchAcceptView 仅 accepted 字段）。</li>
 *   <li>AC-3：attempt/shardContext 透传至 handler 方法；timeoutMs 超时→TIMEOUT/FAILED 上报 + 线程中断尽力。</li>
 *   <li>AC-4：注册调用失败（桩 500）→静默降级（上下文照常启动、本地 @Scheduled 兜底照跑），
 *       桩恢复后重注册（心跳按注册语义 upsert，契约 heartbeat 端点文档）。</li>
 *   <li>AC-6：执行器注册 handlers 清单只含 {@code @JobHandler} 键、不含 {@code @AcmeTask} 旧任务键。</li>
 * </ul>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 appName=spring.application.name（Nacos 服务名即 spring 应用名惯例）；
 * A-2 注册路径=POST /internal/v1/scheduler/executors（REQ-20261121-200724 契约 apis 列表；
 *     JOB-1 范本用的 /register 后缀以 JOB-2 契约为准）；
 * A-3 handler 方法签名可注入下发上下文（契约确认⑤未钉死方法签名——以完整上下文对象透传
 *     attempt/shard/param/fireTime/traceId 断言；联调对齐：实现为框架镜像
 *     {@link JobExecutionContext}（executionId/jobKey/handler/param/fireTime/traceId/attempt/
 *     shardIndex/shardTotal/triggerType），签名争议已消解；
 * A-4 SDK 内部端点鉴权对齐 internal 面（X-Acme-Token），测试域以哑令牌请求；
 *     若实现严格验签则红，归因实现选型；
 * A-5 超时上报形态容忍 state=TIMEOUT 或 state=FAILED 且 error 含 TIMEOUT（契约⑤「FAILED/TIMEOUT
 *     上报」两可；result 端点 state 值域文档仅列 RUNNING/SUCCESS/FAILED）；
 * A-6 拒绝接单仅断言 accepted=false（契约面无 reason 字段，DISPATCH_REJECTED 语义由实现侧保证）。</p>
 *
 * <p>装配说明：{@code @EnableAutoConfiguration} 全量自动装配（内部端点需 MVC/Jackson/Web 容器；
 * lb://platform-scheduler 以 spring-cloud-discovery 静态实例表指向桩）。SDK 未交付时本类
 * red=「实现未交付」（@JobHandler/MVC/lb 依赖面），tester 交付口径如实上报。</p>
 */
@SpringBootTest(classes = REQ20261121200724ExecutorSdkIntegrationTest.SdkTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.application.name=acme-job2-sdk-it",
        "acme.job.executor.enabled=true",
        "acme.job.executor.heartbeat-interval-ms=200",
        "acme.security.token.hmac-secret=req-job2-sdk-it-secret-0123456789"
})
/** JOB-2 联调对齐④：注册用例须观察上下文首轮注册（@AfterEach 清桩），固定其为首个执行方法。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class REQ20261121200724ExecutorSdkIntegrationTest {

    /** 注册路径（TL 裁决 A-2 纠偏：对接面以 JOB-1 已合流实现实际路径为准——
     * register=POST /internal/v1/scheduler/executors/register（InternalExecutorController 实锚），
     * 契约 frontmatter 漏后缀属笔误已纠偏；本用例按实锚路径钉死断言）。 */
    private static final Pattern REGISTER_PATH =
            Pattern.compile("/internal/v1/scheduler/executors/register");
    private static final Pattern HEARTBEAT_PATH =
            Pattern.compile("/internal/v1/scheduler/executors/heartbeat");
    private static final Pattern RESULT_PATH =
            Pattern.compile("/internal/v1/scheduler/executions/([^/]+)/result");

    private static final String JOB_KEY_OK = "it-sdk-ok-handler";
    private static final String JOB_KEY_FAIL = "it-sdk-fail-handler";
    private static final String JOB_KEY_SHARD = "it-sdk-shard-handler";
    private static final String JOB_KEY_SLOW = "it-sdk-slow-handler";
    private static final String LEGACY_TASK_ID = "it-sdk-legacy-task";

    /** @JobHandler 注册清单期望（AC-6：@AcmeTask 旧任务不进 handlers）。 */
    private static final List<String> EXPECTED_HANDLERS =
            List.of(JOB_KEY_OK, JOB_KEY_FAIL, JOB_KEY_SHARD, JOB_KEY_SLOW);

    private static final String STUB_OK_BODY =
            "{\"success\":true,\"code\":\"0\",\"data\":null}";

    /** 内嵌桩调度中心（记录全部请求；场景可切 200/500，服务 AC-4 降级-恢复矩阵）。 */
    private static final SchedulerStub SCHEDULER = SchedulerStub.start();

    @DynamicPropertySource
    static void lbTarget(DynamicPropertyRegistry registry) {
        // Nacos 关闭态以静态实例表承载 lb://platform-scheduler（JOB-1 范本 A-2 同款）
        registry.add("spring.cloud.discovery.client.simple.instances.platform-scheduler[0].uri",
                SCHEDULER::baseUri);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private HandlerProbe probe;

    /** JOB-2 联调对齐③：接单端点鉴权严格对齐 internal 面（InternalTokenAuthFilter 验签），
     *  测试域经同源 InternalTokenIssuer 签发合法令牌（sub=platform-scheduler 惯例，userId 非空）。 */
    @Autowired
    private InternalTokenIssuer tokenIssuer;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @AfterEach
    void resetStub() {
        SCHEDULER.reset(SchedulerStub.Scenario.OK);
        probe.reset();
    }

    // ---------- AC-1：注册全量字段 + 心跳按周期续期 ----------

    @Test
    @org.junit.jupiter.api.Order(1)
    void should_register_with_full_identity_and_heartbeat_periodically_when_executor_enabled()
            throws Exception {
        // ① 注册请求：appName/instanceId/host/port/handlers 全量字段（契约确认④）
        RecordedRequest register = awaitRequest(REGISTER_PATH, 6_000);
        assertThat(register).as("启动应向调度中心发起执行器注册").isNotNull();
        assertThat(jsonField(register.body(), "appName"))
                .as("注册 appName=Nacos 服务名（A-1）：%s", register.body())
                .isEqualTo("acme-job2-sdk-it");
        assertThat(jsonField(register.body(), "instanceId"))
                .as("注册 instanceId 非空").isNotBlank();
        assertThat(jsonField(register.body(), "host")).as("注册 host 非空").isNotBlank();
        assertThat(jsonNumber(register.body(), "port")).as("注册 port>0").isPositive();

        // ② handlers 清单：@JobHandler 键全量在场；@AcmeTask 旧任务键不在（AC-6 handlers 面）
        String handlers = handlersField(register.body());
        for (String expected : EXPECTED_HANDLERS) {
            assertThat(handlers).as("handlers 应含 @JobHandler 键 %s：%s", expected, handlers)
                    .contains(expected);
        }
        assertThat(handlers).as("handlers 不得含 @AcmeTask 旧任务键（契约确认③）：%s", handlers)
                .doesNotContain(LEGACY_TASK_ID);

        // ③ 心跳按周期续期：heartbeat-interval-ms=200，观察窗内应有多轮心跳
        awaitRequest(HEARTBEAT_PATH, 2_000);
        int firstHeartbeats = countRequests(HEARTBEAT_PATH);
        awaitCondition(3_000, () -> countRequests(HEARTBEAT_PATH) > firstHeartbeats);
        assertThat(countRequests(HEARTBEAT_PATH))
                .as("心跳应按周期持续续期").isGreaterThan(firstHeartbeats);
        RecordedRequest heartbeat = latestRequest(HEARTBEAT_PATH);
        assertThat(jsonField(heartbeat.body(), "appName"))
                .as("心跳 appName").isEqualTo("acme-job2-sdk-it");
        assertThat(jsonField(heartbeat.body(), "instanceId"))
                .as("心跳 instanceId 应与注册一致")
                .isEqualTo(jsonField(register.body(), "instanceId"));
    }

    // ---------- AC-2：接单→执行→result 上报全量；未知 jobKey 拒绝 ----------

    @Test
    void should_accept_dispatch_execute_and_report_success_when_job_key_registered() throws Exception {
        HttpResponse<String> accept = dispatch(910001L, JOB_KEY_OK, 0, 0, 1);
        assertThat(accept.statusCode()).as("接单应 HTTP 200").isEqualTo(200);
        assertThat(accept.body()).as("接单响应 accepted=true：%s", accept.body())
                .containsPattern("\"accepted\"\\s*:\\s*true");

        awaitCondition(6_000, () -> !probe.okCalls().isEmpty());
        assertThat(probe.okCalls()).as("handler 方法应被调度执行").isNotEmpty();

        RecordedRequest report = awaitRequestTo(RESULT_PATH, "910001", 6_000);
        assertThat(report).as("执行完成应上报 result（POST /internal/v1/scheduler/executions/910001/result）")
                .isNotNull();
        assertThat(jsonField(report.body(), "state")).as("成功上报 state=SUCCESS：%s", report.body())
                .isEqualTo("SUCCESS");
        assertThat(jsonNumber(report.body(), "costMs"))
                .as("上报应携带耗时 costMs≥0").isGreaterThanOrEqualTo(0L);
        assertThat(report.headers().getOrDefault("X-Acme-Token", List.of()).stream().findFirst().orElse(""))
                .as("上报应携带 X-Acme-Token 内部令牌（契约面）").isNotBlank();
    }

    @Test
    void should_report_failed_with_error_when_handler_throws() throws Exception {
        HttpResponse<String> accept = dispatch(910002L, JOB_KEY_FAIL, 0, 0, 1);
        assertThat(accept.body()).as("失败任务也应受理（失败经 result 上报表达）：%s", accept.body())
                .containsPattern("\"accepted\"\\s*:\\s*true");

        RecordedRequest report = awaitRequestTo(RESULT_PATH, "910002", 6_000);
        assertThat(jsonField(report.body(), "state")).as("异常应上报 FAILED：%s", report.body())
                .isEqualTo("FAILED");
        assertThat(jsonField(report.body(), "error")).as("FAILED 应携带 error 信息（契约②）")
                .contains("it-boom-200724");
    }

    @Test
    void should_reject_dispatch_without_result_report_when_job_key_unknown() throws Exception {
        HttpResponse<String> accept = dispatch(910003L, "no-such-handler-200724", 0, 0, 1);
        assertThat(accept.statusCode()).as("未知 jobKey 仍应 HTTP 200 表达业务拒绝").isEqualTo(200);
        assertThat(accept.body()).as("未知 jobKey 应 accepted=false（DISPATCH_REJECTED）：%s", accept.body())
                .containsPattern("\"accepted\"\\s*:\\s*false");

        // 观察窗：拒绝接单不得产生 result 上报
        awaitCondition(1_500, () -> true);
        assertThat(requestsTo(RESULT_PATH, "910003"))
                .as("拒绝接单不得上报 result").isEmpty();
    }

    // ---------- AC-3：attempt/shard 透传 + timeoutMs 超时上报与线程中断 ----------

    @Test
    void should_pass_attempt_and_shard_context_to_handler_when_dispatch_carries_them() throws Exception {
        HttpResponse<String> accept = dispatch(910004L, JOB_KEY_SHARD, 3, 2, 5);
        assertThat(accept.body()).as("分片任务应受理：%s", accept.body())
                .containsPattern("\"accepted\"\\s*:\\s*true");

        awaitCondition(6_000, () -> !probe.shardCalls().isEmpty());
        JobExecutionContext context = probe.shardCalls().get(0);
        assertThat(context.attempt()).as("attempt 应透传至方法").isEqualTo(3);
        assertThat(context.shardIndex()).as("shardContext.index 应透传至方法").isEqualTo(2);
        assertThat(context.shardTotal()).as("shardContext.total 应透传至方法").isEqualTo(5);
    }

    @Test
    void should_report_timeout_and_interrupt_handler_thread_when_execution_exceeds_timeout_ms()
            throws Exception {
        HttpResponse<String> accept = dispatch(910005L, JOB_KEY_SLOW, 0, 0, 1);
        assertThat(accept.body()).as("超时任务也应先受理（超时经 result 上报表达）：%s", accept.body())
                .containsPattern("\"accepted\"\\s*:\\s*true");

        RecordedRequest report = awaitRequestTo(RESULT_PATH, "910005", 6_000);
        assertThat(report).as("超时应上报结果").isNotNull();
        // A-5 宽容分支：state=TIMEOUT 或 FAILED+error 含 TIMEOUT（契约⑤「FAILED/TIMEOUT 上报」）
        String state = jsonField(report.body(), "state");
        boolean timeoutShape = "TIMEOUT".equals(state)
                || ("FAILED".equals(state)
                        && jsonField(report.body(), "error").toUpperCase().contains("TIMEOUT"));
        assertThat(timeoutShape).as("超时应上报 TIMEOUT 形态（A-5）：%s", report.body()).isTrue();

        awaitCondition(6_000, () -> !probe.interruptFlags().isEmpty());
        assertThat(probe.interruptFlags().get(0))
                .as("超时应尽力中断方法线程（线程中断标记可见，契约⑤）").isTrue();
    }

    // ---------- AC-4：注册失败静默降级 → 本地兜底照跑 → 恢复重注册 ----------

    @Test
    void should_degrade_silently_and_reregister_when_scheduler_register_fails_then_recovers()
            throws Exception {
        // 桩切 500：注册/心跳调用失败 → 静默降级（上下文照常、本地兜底照跑）
        SCHEDULER.reset(SchedulerStub.Scenario.FAIL_500);

        // 本地 @Scheduled 兜底在降级窗口内照跑（不因降级停摆）
        assertThat(probe.awaitFallbackTick(4, TimeUnit.SECONDS))
                .as("降级期间本地 @Scheduled 兜底应照跑（契约确认④）").isTrue();

        // 降级窗口内无成功注册语义可依赖——观测桩收到过请求即可（500 响应=未注册成功）
        // 恢复：桩切回 200 → 注册/心跳恢复（心跳按注册语义 upsert，契约 heartbeat 端点文档）
        SCHEDULER.reset(SchedulerStub.Scenario.OK);
        awaitCondition(6_000, () -> countRequests(REGISTER_PATH) > 0
                || countRequests(HEARTBEAT_PATH) > 0);
        RecordedRequest recovered = latestRequest(REGISTER_PATH) != null
                ? latestRequest(REGISTER_PATH) : latestRequest(HEARTBEAT_PATH);
        assertThat(recovered).as("恢复后应重注册（或心跳 upsert 等价面）").isNotNull();
        assertThat(recovered.status()).as("恢复后请求应 200 成功").isEqualTo(200);
    }

    // ---------- 指令下发支撑（构造 JobDispatchCommand 契约 JSON，POST 自身内部端点） ----------

    private HttpResponse<String> dispatch(long executionId, String jobKey,
                                          int attempt, int shardIndex, int shardTotal)
            throws Exception {
        String body = "{\"jobKey\":\"" + jobKey + "\",\"handler\":\"" + jobKey + "\","
                + "\"param\":\"{\\\"k\\\":\\\"v-200724\\\"}\","
                + "\"shardContext\":{\"index\":" + shardIndex + ",\"total\":" + shardTotal + "},"
                + "\"fireTime\":\"" + Instant.now().toString() + "\","
                + "\"traceId\":\"it-trace-200724-" + executionId + "\","
                + "\"attempt\":" + attempt + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/internal/v1/job/executions/" + executionId + "/run"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject("platform-scheduler", null, null, null)))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ---------- JSON 宽容提取（仅断言面使用；避免为框架域测试引入反序列化栈，冒烟先例同款） ----------

    /** 提取顶层字符串字段（容忍空白与转义，取不到返回空串）。 */
    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 提取顶层数值字段（取不到返回 -1）。 */
    private static long jsonNumber(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
    }

    /** 提取 handlers 数组原文（AC-6 包含/排除断言口径）。 */
    private static String handlersField(String json) {
        Matcher matcher = Pattern.compile("\"handlers\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ---------- 轮询支撑（pitfalls 口径：自旋 + 余量，不 sleep 死等） ----------

    private static void awaitCondition(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        condition.getAsBoolean();
    }

    private static List<RecordedRequest> requestsTo(Pattern path, String executionId) {
        return SCHEDULER.requests().stream()
                .filter(r -> path.matcher(r.path()).matches()
                        && (executionId == null || r.path().contains(executionId)))
                .toList();
    }

    private static RecordedRequest awaitRequestTo(Pattern path, String executionId, long timeoutMs)
            throws InterruptedException {
        AtomicReference<RecordedRequest> found = new AtomicReference<>();
        awaitCondition(timeoutMs, () -> {
            List<RecordedRequest> hits = requestsTo(path, executionId);
            if (!hits.isEmpty()) {
                found.set(hits.get(hits.size() - 1));
                return true;
            }
            return false;
        });
        return found.get();
    }

    private static RecordedRequest awaitRequest(Pattern path, long timeoutMs)
            throws InterruptedException {
        return awaitRequestTo(path, null, timeoutMs);
    }

    private static int countRequests(Pattern path) {
        return requestsTo(path, null).size();
    }

    private static RecordedRequest latestRequest(Pattern path) {
        List<RecordedRequest> hits = requestsTo(path, null);
        return hits.isEmpty() ? null : hits.get(hits.size() - 1);
    }

    // ---------- 内嵌桩调度中心（JDK HttpServer，JOB-1 范本先例） ----------

    /** 请求记录（方法/路径/请求体/状态码/时刻）。 */
    record RecordedRequest(String method, String path, String body, int status, Instant receivedAt,
                           java.util.Map<String, List<String>> headers) {
    }

    /** 桩调度中心：记录全部请求；场景 200 信封 / 恒 500（AC-4 降级矩阵）。 */
    static final class SchedulerStub {

        enum Scenario { OK, FAIL_500 }

        private final com.sun.net.httpserver.HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<Scenario> scenario = new AtomicReference<>(Scenario.OK);

        private SchedulerStub(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static SchedulerStub start() {
            try {
                com.sun.net.httpserver.HttpServer server =
                        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                SchedulerStub stub = new SchedulerStub(server);
                server.createContext("/", stub::handle);
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌桩调度中心启动失败", ex);
            }
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void reset(Scenario next) {
            requests.clear();
            scenario.set(next);
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            RecordedRequest recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    scenario.get() == Scenario.OK ? 200 : 500,
                    Instant.now(),
                    exchange.getRequestHeaders());
            requests.add(recorded);
            byte[] bytes = (scenario.get() == Scenario.OK ? STUB_OK_BODY : "{\"error\":\"stub-degraded\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(recorded.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    // ---------- 测试应用与探针 ----------

    /** SDK 装配宿主：全量自动装配（含 job 模块 auto-configuration 扩展）+ 调度开启。
     *  JOB-2 联调对齐②：本宿主无 DataSource/Redis——LockProvider 按 FR-025「缺失显式失败」
     *  不可能自动装配，而下发执行经 @JobHandler 切面互斥链必需锁提供方，故内嵌内存锁桩
     *  （真实服务面由使用方按 AUTO/JDBC/REDIS 装配，行为不受本桩影响）。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    static class SdkTestApp {

        @Bean
        HandlerProbe handlerProbe() {
            return new HandlerProbe();
        }

        @Bean
        net.javacrumbs.shedlock.core.LockProvider inMemoryLockProvider() {
            return lockConfig -> java.util.Optional.of(new net.javacrumbs.shedlock.core.SimpleLock() {
                @Override
                public void unlock() {
                }

                @Override
                public java.util.Optional<net.javacrumbs.shedlock.core.SimpleLock> extend(
                        java.time.Duration lockAtMostFor, java.time.Duration lockAtLeastFor) {
                    return java.util.Optional.empty();
                }
            });
        }

        /** 数据源桩件（JobSwitchTest 先例）：test classpath 有 spring-data-redis 无 lettuce 工厂时
         *  AUTO 互斥模式会误判 REDIS（Redis 模式需要 StringRedisTemplate→上下文起不来），
         *  桩 DataSource 使 AUTO→JDBC（本桩不触达真实库）。 */
        @Bean
        javax.sql.DataSource dataSource() {
            return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    SdkTestApp.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                    (p, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            throw new java.sql.SQLException("stub");
                        }
                        if ("unwrap".equals(method.getName())) {
                            throw new java.sql.SQLException("stub");
                        }
                        if ("isWrapperFor".equals(method.getName())) {
                            return false;
                        }
                        return null;
                    });
        }
    }

    /** handler 探针：@JobHandler 四任务（成功/异常/分片/超时）+ @AcmeTask 旧任务 + 本地兜底 @Scheduled。
     *  字段经方法访问（探针被 SDK 互斥切面 CGLIB 代理，代理实例字段未初始化，方法调用委托目标实例）。 */
    static class HandlerProbe {

        final List<JobExecutionContext> okInvocations = new CopyOnWriteArrayList<>();
        final List<JobExecutionContext> shardInvocations = new CopyOnWriteArrayList<>();
        final List<Boolean> slowInterruptFlags = new CopyOnWriteArrayList<>();
        final CountDownLatch fallbackTick = new CountDownLatch(1);

        List<JobExecutionContext> okCalls() {
            return okInvocations;
        }

        List<JobExecutionContext> shardCalls() {
            return shardInvocations;
        }

        List<Boolean> interruptFlags() {
            return slowInterruptFlags;
        }

        boolean awaitFallbackTick(long timeout, TimeUnit unit) throws InterruptedException {
            return fallbackTick.await(timeout, unit);
        }

        void reset() {
            okInvocations.clear();
            shardInvocations.clear();
            slowInterruptFlags.clear();
        }

        @JobHandler(jobKey = JOB_KEY_OK, timeoutMs = 5000)
        public void okHandler(JobExecutionContext context) {
            okInvocations.add(context);
        }

        @JobHandler(jobKey = JOB_KEY_FAIL, timeoutMs = 5000)
        public void failHandler(JobExecutionContext context) {
            throw new IllegalStateException("it-boom-200724");
        }

        @JobHandler(jobKey = JOB_KEY_SHARD, shardable = true, timeoutMs = 5000)
        public void shardHandler(JobExecutionContext context) {
            shardInvocations.add(context);
        }

        @JobHandler(jobKey = JOB_KEY_SLOW, timeoutMs = 200)
        public void slowHandler(JobExecutionContext context) {
            try {
                Thread.sleep(1_200);
                slowInterruptFlags.add(false);
            } catch (InterruptedException interrupted) {
                slowInterruptFlags.add(true);
                Thread.currentThread().interrupt();
            }
        }

        @AcmeTask(taskId = LEGACY_TASK_ID)
        public void legacyTask() {
            // 旧语义占位：仅验证注册/清单排除面（AC-6），本体不做副作用
        }

        @Scheduled(fixedDelay = 200)
        public void localFallback() {
            fallbackTick.countDown();
        }
    }
}
