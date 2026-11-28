package com.acme.bootstrap;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261122-233425（BPM-5）business-leave 请假试点端到端集成测试（bootstrap 单体聚合形态；
 * 契约确认⑤⑥；AC-5/AC-6/AC-7 承载）。
 *
 * <p>流程（契约确认⑤）：POST /admin/v1/leave/requests（用户面，经 SDK WorkflowRuntimeClient
 * 启动流程绑 businessKey）→ web 待办中心审批通过（BPM-4 面）→ HR 备案=ServiceTask 委托
 * （SDK 接收端点路由 handler → 落库 FILED → 自动回调推进）→ 实例 INSTANCE_COMPLETED 事件
 * 自消费按 businessKey 回写终态 DONE/REJECTED。leave 状态全链：APPLYING→APPROVING→FILED→
 * DONE（拒绝腿→REJECTED）。</p>
 *
 * <p>装配口径照 {@code MonolithContextSmokeTest} 先例（MonolithApplication + RANDOM_PORT 真容
 * 器端口 + PG/Redis Testcontainers + 种子密码/hmac 环境键），追加 Kafka 容器与 stream 使能
 * 覆盖（事件自消费腿：单体缺省 stream 关闭，测试域显式开启对齐 framework-stream 装配口径）；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证。</p>
 *
 * <p>【等实现标注】business-leave 模块与其 bootstrap 聚合由 backend 并行交付；实现未落地前
 * 本用例无法运行（leave 端点 404 / 事件链断裂），属 tester 交付口径如实标注，不伪造通过。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 申请请求体/响应字段=契约⑤表字段 camelCase 形态（leaveType/reason/startTime/endTime；
 *     响应 data.id 宽容解析）；字段漂移=披露差异 TL 裁决；
 * A-2 leave_request 表 schema 未钉死（business-leave V1 归属 schema 随实现）——本类经
 *     information_schema 定位表名动态解析 schema（字段断言只钉契约⑤原文字段：status/
 *     instance_id）；列值语义钉死=状态机五态字符串；
 * A-3 审批任务锚：试点 BPMN 审批节点指派面未钉死——以 admin（发起人/超管）todo 为锚；
 *     若审批指派他人，红=指派规则披露差异 TL 裁决；
 * A-4 APPROVING 落点=审批任务完成之后、HR 备案之前（契约⑥状态全链序推）；FILED 落点=
 *     HR 备案 handler 落库（确认⑤）；DONE/REJECTED 落点=事件自消费回写（AC-7）——
 *     三者均以 DB 状态轮询承载（自旋+余量，pitfalls 15 口径）。
 *     【REQ-20261128-092210 时序适配】新实现下审批办结存在双腿：TASK_COMPLETED 事件腿
 *     （APPROVING）与 hr-file ServiceTask 下发腿（下发权威推进两步前向 APPROVING→FILED，
 *     确认①），下发腿先到时 FILED 直接落库（乐观锁收敛、只推进一次），APPROVING 不再必然
 *     作为独立可观测态出现——审批办结后的中间态等待放宽为 {APPROVING, FILED}，随后仍以
 *     FILED 为备案落库锚、DONE 为终态锚，状态链 APPLYING→APPROVING→FILED→DONE 收敛不变；
 * A-5 拒绝腿：BPM-4 reject（targetNodeId 缺省形态）在试点 BPMN 上终结实例 → 事件回写
 *     REJECTED；若试点 BPMN reject 语义为回退非终结，红=BPMN 形态披露差异 TL 裁决；
 * A-6 DONE 的因果归因（事件自消费 vs 同步落库）黑盒不可分辨——本用例以「终态最终落库」为
 *     oracle，事件链真伪由 workflow-service 事件 IT（AC-4）+ 本用例 FILED→DONE 间无同步
 *     操作的组合承载；oracle 局限如实披露；
 * A-7 单体 stream 使能：本类 @SpringBootTest properties 覆盖测试域 yml 的 stream 关闭态
 *     （事件自消费腿必需）；Kafka binder brokers 指向容器。</p>
 */
@SpringBootTest(classes = MonolithApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // A-7：事件自消费腿——单体 stream 显式开启（缺省关闭，测试域覆盖）
                "acme.stream.enabled=true",
                "spring.cloud.stream.enabled=true"
        })
class REQ20261122233425LeaveE2eIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** B 端登录种子密码（MonolithContextSmokeTest 同款环境键）。 */
    private static final String SEED_SUPERADMIN_PASSWORD = "Monolith@123456";

    private static final String LEAVE_REQUESTS_URL = "/admin/v1/leave/requests";
    private static final String TASKS_TODO_URL = "/admin/v1/workflow/tasks/todo";
    private static final String TASK_COMPLETE_URL = "/admin/v1/workflow/tasks/%d/complete";
    private static final String TASK_REJECT_URL = "/admin/v1/workflow/tasks/%d/reject";

    /** 零 Docker 进程内 KRaft Kafka broker（随机空闲端口，不抢占宿主 9092）。 */
    private static final EmbeddedKafkaKraftBroker KAFKA = startEmbeddedKafka();

    private static EmbeddedKafkaKraftBroker startEmbeddedKafka() {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(
                1, 1, "acme.workflow.events", "acme.workflow.events-dlq");
        broker.afterPropertiesSet();
        return broker;
    }

    @AfterAll
    static void stopEmbeddedKafka() {
        KAFKA.destroy();
    }

    private static final java.nio.file.Path STORAGE_ROOT = tempStorageRoot();

    private static java.nio.file.Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("acme-leave-e2e-storage");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("创建存储根目录失败", ex);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        EmbeddedRedisSupport redis = EmbeddedRedisSupport.shared();
        redis.flushAll();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl(
                "acme_monolith_leave_e2e",
                "platform_auth,platform_system,platform_infra,platform_scheduler,platform_workflow,business_leave"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("ACME_SEED_SUPERADMIN_PASSWORD", () -> SEED_SUPERADMIN_PASSWORD);
        registry.add("ACME_SEED_DEMO_ADMIN_PASSWORD", () -> "MonolithDemo@123");
        registry.add("ACME_SEED_DEMO_OPERATOR_PASSWORD", () -> "MonolithDemo@456");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
        registry.add("ACME_USER_TOKEN_HMAC_SECRET", () -> "leave-e2e-user-token-secret-0123456789abcdef");
        registry.add("ACME_SECURITY_TOKEN_HMAC_SECRET", () -> "leave-e2e-security-token-secret-0123456789");
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBrokersAsString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据（每测试类独立 PG 容器；schema 经 information_schema 定位）。
        // 注意：只清 leave_request/leave_start_task 行集（REQ-20261128-092210 P1-03 补偿任务行随
        // 单据清理，防重试任务扫描捞取陈旧行对已删单据补投），wf_* 表不得逐用例清空——试点流程由
        // LeavePilotProcessDeployer 启动期部署一次并缓存 definitionId（逐用例清 wf_process_definition
        // 会让下一用例 DEFINITION_NOT_FOUND，实证 02:47 轮），wf 行按 businessKey/实例 id 天然隔离
        // 不清；类容器随类销毁，无跨类残留面。
        try {
            jdbcTemplate.update("delete from " + qualified("leave_request"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // 表未随实现落地（等实现期）：跳过清理，测试自身会给出明确信号
        }
        try {
            jdbcTemplate.update("delete from " + qualified("leave_start_task"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // V2 迁移未落地（等实现期）：跳过清理
        }
    }

    // ---------- AC-5/AC-6：请假全链（申请→审批→备案→事件回写 DONE） ----------

    @Test
    void should_run_leave_full_chain_when_request_approved_and_filed() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-e2e-" + SEQ.incrementAndGet();

        // ① 申请（用户面）→ APPLYING + 实例绑定
        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tester-e2e",
                 "startTime":"2026-12-01T09:00:00","endTime":"2026-12-02T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功（A-1 请求/响应形态）");
        long leaveId = idOf(created);
        assertThat(awaitStatus(leaveId, "APPLYING", 15_000))
                .as("申请后 leave 应 APPLYING，实际=%s", leaveStatus(leaveId)).isTrue();
        long instanceId = instanceIdOf(leaveId);
        assertThat(instanceId).as("申请应绑定 workflow 实例（instance_id 回写）").isPositive();

        // ② web 待办中心审批通过（BPM-4 面）→ APPROVING
        long approvalTaskId = findTodoTaskId(token, instanceId);
        assertThat(approvalTaskId).as("审批任务应出现在 admin todo（A-3）").isPositive();
        ok(postJson(token, TASK_COMPLETE_URL.formatted(approvalTaskId), "{}"),
                "审批通过（complete）应成功");
        // REQ-20261128-092210 时序适配：事件腿（APPROVING）与下发腿（两步前向 APPROVING→FILED）
        // 竞态由乐观锁收敛，下发腿先到时 APPROVING 不作为独立可观测态出现——中间态等待放宽为
        // {APPROVING, FILED}，随后仍以 FILED 为备案落库锚（A-4 适配注记）。
        assertThat(awaitStatusIn(leaveId, java.util.Set.of("APPROVING", "FILED"), 20_000))
                .as("审批通过后 leave 应至少推进到 APPROVING/FILED（双腿收敛），实际=%s", leaveStatus(leaveId))
                .isTrue();

        // ③ HR 备案=ServiceTask 委托：SDK 接收端点路由 handler → 落库 FILED → 自动回调推进
        //    （AC-5 SERVICE_TASK 全链通；无手工回调操作，全链由 SDK 委托自动完成）
        assertThat(awaitStatus(leaveId, "FILED", 45_000))
                .as("HR 备案 handler 应落库 FILED（AC-5），实际=%s", leaveStatus(leaveId)).isTrue();

        // ④ 实例 COMPLETED + 事件自消费回写终态 DONE（AC-6/AC-7）
        assertThat(await(45_000, () -> "COMPLETED".equals(workflowInstanceStatus(instanceId))))
                .as("备案回调后实例应执行到终点，实际=%s", workflowInstanceStatus(instanceId)).isTrue();
        assertThat(awaitStatus(leaveId, "DONE", 45_000))
                .as("INSTANCE_COMPLETED 事件自消费应回写 DONE（AC-7），实际=%s", leaveStatus(leaveId))
                .isTrue();
    }

    // ---------- AC-7：拒绝腿终态回写 ----------

    @Test
    void should_write_rejected_terminal_state_when_approval_rejected() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-e2e-reject-" + SEQ.incrementAndGet();

        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"sick","reason":"tester-e2e-reject",
                 "startTime":"2026-12-05T09:00:00","endTime":"2026-12-06T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(awaitStatus(leaveId, "APPLYING", 15_000))
                .as("申请后 leave 应 APPLYING，实际=%s", leaveStatus(leaveId)).isTrue();
        long instanceId = instanceIdOf(leaveId);

        // 审批拒绝（BPM-4 reject 缺省形态）→ 试点 BPMN 终结 → 事件回写 REJECTED（A-5）
        long approvalTaskId = findTodoTaskId(token, instanceId);
        assertThat(approvalTaskId).as("审批任务应出现在 admin todo（A-3）").isPositive();
        ok(postJson(token, TASK_REJECT_URL.formatted(approvalTaskId),
                        "{\"comment\":\"tester-e2e-reject\"}"),
                "审批拒绝应受理");
        assertThat(awaitStatus(leaveId, "REJECTED", 45_000))
                .as("拒绝腿事件自消费应回写 REJECTED（AC-7），实际=%s", leaveStatus(leaveId))
                .isTrue();
    }

    // ---------- HTTP 支撑（MonolithContextSmokeTest 先例：真容器端口全过滤链） ----------

    private String loginAdmin() throws Exception {
        HttpResponse<String> login = postJson(null, "/admin/v1/auth/tokens", """
                {"tenantCode":"default","username":"admin","password":"%s"}
                """.formatted(SEED_SUPERADMIN_PASSWORD));
        assertThat(login.statusCode()).as("登录应 200：%s", login.body()).isEqualTo(200);
        String body = login.body();
        assertThat(body).contains("\"success\":true");
        String token = extractJsonStringField(body, "accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String bearerToken, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode bodyOf(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    private JsonNode ok(HttpResponse<String> response, String desc) {
        assertThat(response.statusCode())
                .as(desc + " 不应 5xx：%s %s", response.statusCode(), response.body())
                .isLessThan(500);
        return ok(bodyOf(response), desc);
    }

    /** data.id 宽容解析（A-1：data.id / data 裸值；Long wire=字符串 FR-024）。 */
    private long idOf(JsonNode resp) {
        JsonNode data = resp.path("data");
        JsonNode id = data.has("id") ? data.path("id") : data;
        return id.asLong();
    }

    private static String extractJsonStringField(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("响应应包含字段 %s：%s", field, json).isTrue();
        return matcher.group(1);
    }

    // ---------- 待办中心支撑（A-3） ----------

    /** admin todo 中本实例的审批任务 id（instanceId 投影宽容解析；分页 size 上界内）。 */
    private long findTodoTaskId(String token, long instanceId) throws Exception {
        long[] found = {0L};
        await(15_000, () -> {
            try {
                JsonNode resp = bodyOf(get(TASKS_TODO_URL + "?page=1&size=200", token));
                if (!resp.path("success").asBoolean()) {
                    return false;
                }
                JsonNode data = resp.path("data");
                JsonNode items = data.has("records") ? data.path("records") : data;
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        JsonNode itemInstance = item.path("instanceId");
                        if (!itemInstance.isMissingNode()
                                && String.valueOf(itemInstance.asLong()).equals(String.valueOf(instanceId))) {
                            found[0] = item.path("id").asLong();
                            return true;
                        }
                    }
                }
                return false;
            } catch (Exception ex) {
                return false;
            }
        });
        return found[0];
    }

    // ---------- DB 夹具读取（A-2：information_schema 定位，schema 未钉死） ----------

    /** leave_request / wf_* 表的 schema 限定名（测试容器内唯一表名，定位失败返回裸名触发可读异常）。 */
    private String qualified(String tableName) {
        List<String> schemas = jdbcTemplate.queryForList(
                "select table_schema from information_schema.tables "
                        + "where table_name = ? and table_schema not in ('pg_catalog','information_schema')",
                String.class, tableName);
        return schemas.isEmpty() ? tableName : schemas.get(0) + "." + tableName;
    }

    private String leaveStatus(long leaveId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "select status from " + qualified("leave_request") + " where id = ?",
                    String.class, leaveId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (org.springframework.dao.DataAccessException ex) {
            return null;
        }
    }

    /** leave 行的 instance_id（契约⑤原文字段；未回写返回 -1）。 */
    private long instanceIdOf(long leaveId) {
        try {
            List<Long> rows = jdbcTemplate.queryForList(
                    "select instance_id from " + qualified("leave_request") + " where id = ?",
                    Long.class, leaveId);
            return rows.isEmpty() || rows.get(0) == null ? -1L : rows.get(0);
        } catch (org.springframework.dao.DataAccessException ex) {
            return -1L;
        }
    }

    private String workflowInstanceStatus(long instanceId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "select status from " + qualified("wf_process_instance") + " where id = ?",
                    String.class, instanceId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (org.springframework.dao.DataAccessException ex) {
            return null;
        }
    }

    /** 状态轮询（A-4：目标状态在窗口内出现）。 */
    private boolean awaitStatus(long leaveId, String expected, long timeoutMs) {
        return await(timeoutMs, () -> expected.equals(leaveStatus(leaveId)));
    }

    /** 状态轮询（REQ-20261128-092210 适配：双腿竞态收敛的中间态放宽为集合命中）。 */
    private boolean awaitStatusIn(long leaveId, java.util.Set<String> expected, long timeoutMs) {
        return await(timeoutMs, () -> expected.contains(leaveStatus(leaveId)));
    }

    private static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
