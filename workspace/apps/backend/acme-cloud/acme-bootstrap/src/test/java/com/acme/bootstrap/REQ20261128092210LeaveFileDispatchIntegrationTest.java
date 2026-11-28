package com.acme.bootstrap;

import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.common.context.TenantContext;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-092210（B4-事务 P1-04）HR 备案下发状态机集成观测（bootstrap 单体聚合形态；
 * AC-11/AC-13/AC-14 集成面承载；AC-12/15/16 单测层归 backend-dev）。
 *
 * <p>装配口径照 {@code REQ20261122233425LeaveE2eIntegrationTest} 先例：stream 显式开启
 * （事件腿必需：APPROVING 唯一推进入口=TASK_COMPLETED 消费）+ 零 Docker 内嵌 KRaft Kafka +
 * 嵌入式 PG/Redis + 独立隔离库 {@code acme_monolith_leave_filetask}。单体测试域
 * {@code acme.workflow.service-task.receive-async=true}（测试域 yml 钉面）：审批办结→下发受理→
 * handler 延迟执行→FILED→自动回调推进，事件腿 APPROVING 先于 handler 落库为常态，双腿竞态由
 * 乐观锁收敛（只推进一次、终态不回退）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 下发驱动缝=Spring 注入真 {@code LeaveRequestUseCase} bean 直调
 *     {@code fileByServiceTask(Long instanceId, String businessKey)}（签名=字节码钉面 +
 *     契约 P1-03/P1-04 钉名；禁 new UseCase——经容器注入；参数锚=leave_request.instance_id
 *     实值 + 实例 business_key 实值（wf_process_instance 行读取，读不到回退请求原值））——
 *     等价 SDK 接收端点路由到 handler 后的业务调用，绕过 internal HTTP 认证面（未钉死）；
 *     直调不触发 SDK 自动回调（回调归接收端点层），实例侧推进由真实下发链在 AC-11/AC-14(DONE)
 *     全链用例中独立承载；【实测披露，TL 裁决】直调须绑定单据租户上下文（无上下文 resolveByRuntime
 *     报「请假单不存在」——flex 租户护栏无上下文不命出行集，与补偿链扫描同款观测），
 *     {@code dispatchFiling} 按单据 tenant_id 回放后调用、finally 清理；
 * A-2 handler 返回 Map 的键 filed/reason 按契约 AC-13/AC-14 原文断言（String.valueOf 宽容形态）；
 * A-3 AC-13 前置「请假单已 FILED」经下发权威推进构造（APPLYING 收下发即两步前向
 *     APPROVING→FILED，契约确认①）——审批任务保持未办结，无事件腿/回调腿在途，重复下发断言
 *     无竞态；handler 只锚 instanceId/businessKey（签名两参无节点态入参），不校验引擎节点位置；
 * A-4 AC-11 中间态等待放宽为 {APPROVING, FILED}：下发腿先到时两步前向直接落 FILED
 *     （乐观锁收敛），APPROVING 不再必然作为独立可观测态出现；FILED 为备案落库锚；
 * A-5 teardown 清 leave_request/leave_start_task 行集（范本形制）；wf_* 行按 businessKey/
 *     实例 id 天然隔离不清（逐用例清 wf_process_definition 会破坏试点部署缓存，范本实证注记）；
 *     隔离库随类销毁无跨类残留面。</p>
 */
@SpringBootTest(classes = MonolithApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 事件自消费腿——单体 stream 显式开启（缺省关闭，测试域覆盖；范本 A-7 同款）
                "acme.stream.enabled=true",
                "spring.cloud.stream.enabled=true"
        })
class REQ20261128092210LeaveFileDispatchIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** B 端登录种子密码（MonolithContextSmokeTest 同款环境键）。 */
    private static final String SEED_SUPERADMIN_PASSWORD = "Monolith@123456";

    private static final String LEAVE_REQUESTS_URL = "/admin/v1/leave/requests";
    private static final String TASKS_TODO_URL = "/admin/v1/workflow/tasks/todo";
    private static final String TASK_COMPLETE_URL = "/admin/v1/workflow/tasks/%d/complete";
    private static final String TASK_REJECT_URL = "/admin/v1/workflow/tasks/%d/reject";

    /** 零 Docker 进程内 KRaft Kafka broker（随机空闲端口，范本同款）。 */
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
            return Files.createTempDirectory("acme-leave-file-dispatch-storage");
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
                "acme_monolith_leave_filetask",
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
        registry.add("ACME_USER_TOKEN_HMAC_SECRET", () -> "leave-file-user-token-secret-0123456789abcdef");
        registry.add("ACME_SECURITY_TOKEN_HMAC_SECRET", () -> "leave-file-security-token-secret-0123456789");
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBrokersAsString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 下发驱动缝（A-1）：真 bean 注入直调备案 handler（禁 new，构造签名零耦合）。 */
    @Autowired
    private LeaveRequestUseCase leaveUseCase;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据（范本形制）：只清 leave_request/leave_start_task 行集；wf 行不清
        try {
            jdbcTemplate.update("delete from " + qualified("leave_start_task"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // V2 迁移未落地（等实现期）：跳过清理
        }
        try {
            jdbcTemplate.update("delete from " + qualified("leave_request"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // 表未随实现落地（等实现期）：跳过清理
        }
    }

    // ---------- AC-11：APPROVING→FILED 正常路径（真实下发全链） ----------

    @Test
    void should_advance_to_filed_when_hr_filing_dispatch_arrives_after_approval() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-file-ac11-" + SEQ.incrementAndGet();

        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tester-file-ac11",
                 "startTime":"2026-12-01T09:00:00","endTime":"2026-12-02T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(await(15_000, () -> "APPLYING".equals(leaveStatus(leaveId))))
                .as("申请后 leave 应 APPLYING").isTrue();
        long instanceId = instanceIdOf(leaveId);
        assertThat(instanceId).as("afterCommit 启动成功应绑定实例").isPositive();

        // 审批办结 → 双腿（事件腿 APPROVING / 下发腿 FILED）竞态收敛（A-4）
        long approvalTaskId = findTodoTaskId(token, instanceId);
        assertThat(approvalTaskId).as("审批任务应出现在 admin todo").isPositive();
        ok(postJson(token, TASK_COMPLETE_URL.formatted(approvalTaskId), "{}"), "审批通过应成功");

        assertThat(awaitStatusIn(leaveId, Set.of("APPROVING", "FILED"), 20_000))
                .as("审批办结后 leave 应至少推进到 APPROVING/FILED（双腿收敛），实际=%s", leaveStatus(leaveId))
                .isTrue();
        assertThat(awaitStatus(leaveId, "FILED", 45_000))
                .as("HR 备案下发应落库 FILED（AC-11 正常路径），实际=%s", leaveStatus(leaveId)).isTrue();
        assertThat(await(45_000, () -> "COMPLETED".equals(workflowInstanceStatus(instanceId))))
                .as("备案回调后实例应执行到终点，实际=%s", workflowInstanceStatus(instanceId)).isTrue();
        assertThat(awaitStatus(leaveId, "DONE", 45_000))
                .as("实例终结事件自消费应回写 DONE，实际=%s", leaveStatus(leaveId)).isTrue();
    }

    // ---------- AC-13：已 FILED 重复下发幂等（filed=true 不变） ----------

    @Test
    void should_return_filed_true_idempotently_when_filing_dispatch_repeats() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-file-ac13-" + SEQ.incrementAndGet();

        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"sick","reason":"tester-file-ac13",
                 "startTime":"2026-12-05T09:00:00","endTime":"2026-12-06T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(await(15_000, () -> instanceIdOf(leaveId) > 0))
                .as("afterCommit 启动成功应绑定实例（A-7）").isTrue();
        long instanceId = instanceIdOf(leaveId);
        String filingKey = filingBusinessKey(instanceId, businessKey);

        // 首次下发：APPLYING 收下发即两步前向 APPROVING→FILED（契约确认①下发权威推进；A-3）
        Map<String, Object> firstDispatch = dispatchFiling(leaveId, instanceId, filingKey);
        assertThat(filedFlag(firstDispatch)).as("首次下发应落库 FILED（filed=true）").isTrue();
        assertThat(awaitStatus(leaveId, "FILED", 5_000))
                .as("下发后 leave 应 FILED，实际=%s", leaveStatus(leaveId)).isTrue();

        // 重复下发：幂等直返 filed=true，状态不回退不重写（AC-13）
        Map<String, Object> repeatDispatch = dispatchFiling(leaveId, instanceId, filingKey);
        assertThat(filedFlag(repeatDispatch)).as("重复下发应幂等直返 filed=true（AC-13）").isTrue();
        assertThat(leaveStatus(leaveId)).as("重复下发不应改变状态（保持 FILED）").isEqualTo("FILED");
    }

    // ---------- AC-14：终态不写（DONE 腿 / REJECTED 腿，filed=false+reason） ----------

    @Test
    void should_not_write_state_when_filing_dispatch_arrives_after_done() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-file-ac14-done-" + SEQ.incrementAndGet();
        long leaveId = applyApproveFileDone(token, businessKey);
        long instanceId = instanceIdOf(leaveId);

        Map<String, Object> lateDispatch = dispatchFiling(leaveId, instanceId,
                filingBusinessKey(instanceId, businessKey));
        assertThat(filedFlag(lateDispatch)).as("终态下发不应写状态（filed=false，AC-14）").isFalse();
        assertThat(String.valueOf(lateDispatch.get("reason")))
                .as("终态下发应返回 reason（AC-14）").isNotBlank();
        assertThat(leaveStatus(leaveId)).as("终态 DONE 不应被改写").isEqualTo("DONE");
    }

    @Test
    void should_not_write_state_when_filing_dispatch_arrives_after_rejected() throws Exception {
        String token = loginAdmin();
        String businessKey = "leave-file-ac14-rej-" + SEQ.incrementAndGet();

        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tester-file-ac14-rej",
                 "startTime":"2026-12-10T09:00:00","endTime":"2026-12-11T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(await(15_000, () -> instanceIdOf(leaveId) > 0)).as("应绑定实例").isTrue();
        long instanceId = instanceIdOf(leaveId);

        long approvalTaskId = findTodoTaskId(token, instanceId);
        assertThat(approvalTaskId).as("审批任务应出现在 admin todo").isPositive();
        ok(postJson(token, TASK_REJECT_URL.formatted(approvalTaskId), "{\"comment\":\"tester-file-ac14-rej\"}"),
                "审批拒绝应受理");
        assertThat(awaitStatus(leaveId, "REJECTED", 45_000))
                .as("拒绝腿事件自消费应回写 REJECTED，实际=%s", leaveStatus(leaveId)).isTrue();

        Map<String, Object> lateDispatch = dispatchFiling(leaveId, instanceId,
                filingBusinessKey(instanceId, businessKey));
        assertThat(filedFlag(lateDispatch)).as("REJECTED 终态下发不应写状态（filed=false，AC-14）").isFalse();
        assertThat(String.valueOf(lateDispatch.get("reason")))
                .as("REJECTED 终态下发应返回 reason（AC-14）").isNotBlank();
        assertThat(leaveStatus(leaveId)).as("终态 REJECTED 不应被改写").isEqualTo("REJECTED");
    }

    // ---------- 用例支撑（范本形制照抄） ----------

    /** 全链基座：申请→审批→备案下发落 FILED→实例终结→事件回写 DONE（终态已收敛）。 */
    private long applyApproveFileDone(String token, String businessKey) throws Exception {
        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tester-file-ac14-done",
                 "startTime":"2026-12-15T09:00:00","endTime":"2026-12-16T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(await(15_000, () -> instanceIdOf(leaveId) > 0)).as("应绑定实例").isTrue();
        long instanceId = instanceIdOf(leaveId);

        long approvalTaskId = findTodoTaskId(token, instanceId);
        assertThat(approvalTaskId).as("审批任务应出现在 admin todo").isPositive();
        ok(postJson(token, TASK_COMPLETE_URL.formatted(approvalTaskId), "{}"), "审批通过应成功");

        assertThat(awaitStatusIn(leaveId, Set.of("APPROVING", "FILED"), 20_000))
                .as("审批办结后应推进（A-4 双腿收敛），实际=%s", leaveStatus(leaveId)).isTrue();
        assertThat(awaitStatus(leaveId, "FILED", 45_000))
                .as("备案下发应落库 FILED，实际=%s", leaveStatus(leaveId)).isTrue();
        assertThat(await(45_000, () -> "COMPLETED".equals(workflowInstanceStatus(instanceId))))
                .as("实例应执行到终点，实际=%s", workflowInstanceStatus(instanceId)).isTrue();
        assertThat(awaitStatus(leaveId, "DONE", 45_000))
                .as("事件自消费应回写 DONE，实际=%s", leaveStatus(leaveId)).isTrue();
        return leaveId;
    }

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

    /**
     * 备案下发直调驱动（A-1/A-6）：按单据租户绑定 TenantContext（等价 SDK 接收端点从 payload
     * 回放租户的面——实测 comp-debug 轮：无上下文直调 resolveByRuntime 报「请假单不存在」，
     * flex 租户护栏无上下文不命出行集，与补偿链扫描同款观测）后调用真 handler bean，finally 清理。
     */
    private Map<String, Object> dispatchFiling(long leaveId, long instanceId, String businessKey) {
        TenantContext.bind(new TenantContext(leaveTenantOf(leaveId)));
        try {
            return leaveUseCase.fileByServiceTask(instanceId, businessKey);
        } finally {
            TenantContext.clear();
        }
    }

    /** 备案下发 businessKey 锚：引擎侧实例行实值（回退请求原值，A-1）。 */
    private String filingBusinessKey(long instanceId, String fallback) {
        try {
            List<String> keys = jdbcTemplate.queryForList(
                    "select business_key from " + qualified("wf_process_instance") + " where id = ?",
                    String.class, instanceId);
            if (!keys.isEmpty() && keys.get(0) != null) {
                return keys.get(0);
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // wf business_key 列形态漂移：回退请求原值（黑盒不断言引擎 schema）
        }
        return fallback;
    }

    /** filed 键宽容解析（A-2：Boolean/String 形态均收敛）。 */
    private boolean filedFlag(Map<String, Object> result) {
        assertThat(result).as("备案 handler 返回应含 filed 键（AC-13/AC-14）").isNotNull().containsKey("filed");
        return Boolean.parseBoolean(String.valueOf(result.get("filed")));
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

    /** data.id 宽容解析（Long wire=字符串 FR-024，范本同款）。 */
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

    // ---------- 待办中心支撑（范本同款） ----------

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

    // ---------- DB 夹具读取（qualified 经 information_schema 定位，范本同款） ----------

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

    private String leaveTenantOf(long leaveId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "select tenant_id from " + qualified("leave_request") + " where id = ?",
                    String.class, leaveId);
            return rows.isEmpty() || rows.get(0) == null ? "default" : rows.get(0);
        } catch (org.springframework.dao.DataAccessException ex) {
            return "default";
        }
    }

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

    private boolean awaitStatus(long leaveId, String expected, long timeoutMs) {
        return await(timeoutMs, () -> expected.equals(leaveStatus(leaveId)));
    }

    private boolean awaitStatusIn(long leaveId, Set<String> expected, long timeoutMs) {
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
