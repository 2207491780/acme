package com.acme.platform.workflow;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-102730（BPM-3）TaskView 投影扩展集成测试（AC-11；tester 专项，
 * 既有 49 例零回归由 TL 全量门禁承载）。
 *
 * <p>契约口径（contracts/REQ-20261122-102730.md 确认⑤/⑥ + AC-11）：</p>
 * <ul>
 *   <li>todo/done/initiated 列表补 definitionKey/definitionName/businessKey/initiator 四字段
 *       全量回显（JOIN definition+instance 一次查询装配）；wire id 一律字符串（定义域先例）；</li>
 *   <li>todo/done 增可选过滤参数 definitionKey/status（缺省不过滤）；</li>
 *   <li>N+1 禁令的黑盒 oracle：一次列表 HTTP 调用的 SQL 语句数不随行数线性增长
 *       （测试内以 DataSource 包装计数，8 行行集语句数上界常数化——per-row JOIN 装配会超界）。</li>
 * </ul>
 *
 * <p>装配照 {@code REQ20261122023441WorkflowLayoutJsonIntegrationTest} / 
 * {@code REQ20261121142316WorkflowEngineIntegrationTest} 先例（Testcontainers PG +
 * MockMvc HTTP 面 + JdbcTemplate 夹具；无 Docker 自动跳过——判定口径：日志须见
 * Tests run: N, Skipped: 0 才算验证）。teardown 物理清共享表（tester 纪律③）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformWorkflowApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req102730-tester-it-secret-0123456789"
})
class REQ20261122102730TaskViewProjectionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEFS_URL = "/admin/v1/workflow/process-definitions";
    private static final String INSTANCES_URL = "/admin/v1/workflow/process-instances";
    private static final String TASKS_URL = "/admin/v1/workflow/tasks";

    private static final MediaType JSON_TYPE = MediaType.APPLICATION_JSON;

    /** 单任务直通（固定 assignee=2501；固定 assignee 亦先 CREATED，todo 直见）。 */
    private static final String SINGLE_TASK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-only"/>
            <userTask id="task-only" name="唯一任务" acme:assignee="2501"/>
            <sequenceFlow id="f1" sourceRef="task-only" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 两级同 assignee 链（done 状态过滤夹具：t1 完成 + t2 驳回 → done 双状态行）。 */
    private static final String TWO_TASK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" name="一级审批" acme:assignee="2501"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="t2"/>
            <userTask id="t2" name="二级审批" acme:assignee="2501"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 办理人（固定 assignee；token 即身份，无需目录数据）。 */
    private static final String ASSIGNEE = "2501";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
    }

    /** N+1 黑盒计数装配：包装容器内 DataSource，统计经其发出的 Statement 创建次数。 */
    @TestConfiguration
    static class StatementCountingConfig {
        @Bean
        static BeanPostProcessor statementCountingPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds) {
                        return new CountingDataSource(ds, SQL_STATEMENTS);
                    }
                    return bean;
                }
            };
        }
    }

    /** 本测试类全部 SQL 语句计数（每次测量前 reset；仅本类上下文消费）。 */
    static final AtomicInteger SQL_STATEMENTS = new AtomicInteger();

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

    @MockitoBean
    private WorkflowIdentityClient workflowIdentityClient;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
        when(authorizationClient.currentBuiltinRoles())
                .thenReturn(new ContractResult<>(true, "0", "ok", Set.of()));
    }

    @AfterEach
    void cleanUp() {
        // teardown：物理清共享表（本类用例是本容器唯一写入方，照引擎测试先例）
        jdbcTemplate.update("delete from wf_timer_job");
        jdbcTemplate.update("delete from wf_history_activity");
        jdbcTemplate.update("delete from wf_variable");
        jdbcTemplate.update("delete from wf_task");
        jdbcTemplate.update("delete from wf_execution");
        jdbcTemplate.update("delete from wf_process_instance");
        jdbcTemplate.update("delete from wf_process_definition");
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- BPMN/HTTP 夹具 ----------

    private static String bpmn(String processId, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:acme="http://acme.dev/bpmn/ext/v1"
                             targetNamespace="http://acme.dev/bpmn/it">
                  <process id="%s" name="%s">
                %s
                  </process>
                </definitions>
                """.formatted(processId, processId, body);
    }

    private String uniq(String suffix) {
        return "bpm3it" + SEQ.incrementAndGet() + suffix;
    }

    private ObjectNode bodyNode() {
        return JSON.createObjectNode();
    }

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
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** 分页/列表 data 形状宽容解析：data.records 优先，退化 data 数组（引擎测试 A-3 口径）。 */
    private List<JsonNode> dataItems(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode records = data.isObject() ? data.path("records") : null;
        if (records != null && records.isArray()) {
            List<JsonNode> items = new java.util.ArrayList<>();
            records.forEach(items::add);
            return items;
        }
        if (data.isArray()) {
            List<JsonNode> items = new java.util.ArrayList<>();
            data.forEach(items::add);
            return items;
        }
        return List.of();
    }

    /** 建草稿 → 保存 XML → 部署，直达 DEPLOYED；返回定义 id。 */
    private long createAndDeploy(String key, String name, String body) throws Exception {
        ObjectNode create = bodyNode();
        create.put("key", key);
        create.put("name", name);
        ok(callAsSuperAdmin(post(DEFS_URL).contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(create))), "创建草稿应成功");
        long id = jdbcTemplate.queryForObject(
                "select id from wf_process_definition where process_key = ?", Long.class, key);
        ObjectNode save = bodyNode();
        save.put("bpmnXml", bpmn(key, body));
        ok(callAsSuperAdmin(put(DEFS_URL + "/" + id + "/xml").contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(save))), "保存 XML 应成功");
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + id + "/deploy")), "部署应成功");
        return id;
    }

    /** 启动实例（发起人=userId）；返回实例 DB id（夹具定位用）。 */
    private long startInstanceAs(String userId, long definitionId, String businessKey) throws Exception {
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(definitionId));
        body.put("businessKey", businessKey);
        JsonNode resp = JSON.readTree(performAs(userId, "default", Set.of("super_admin"),
                post(INSTANCES_URL).contentType(JSON_TYPE)
                        .content(JSON.writeValueAsString(body))).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        ok(resp, userId + " 启动实例应成功");
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_instance where business_key = ?", Long.class, businessKey);
        assertThat(ids).as("实例 %s 应已落库", businessKey).isNotEmpty();
        return ids.get(0);
    }

    /** todo 列表（可携过滤参数）。 */
    private List<JsonNode> todoOf(String userId, String definitionKey, String status) throws Exception {
        StringBuilder url = new StringBuilder(TASKS_URL + "/todo?page=1&size=50");
        if (definitionKey != null) {
            url.append("&definitionKey=").append(definitionKey);
        }
        if (status != null) {
            url.append("&status=").append(status);
        }
        JsonNode resp = JSON.readTree(performAs(userId, "default", Set.of("super_admin"),
                get(url.toString())).getResponse().getContentAsString(StandardCharsets.UTF_8));
        return ok(resp, "todo 查询应成功").path("data") == null ? List.of() : dataItems(resp);
    }

    /** done 列表（可携过滤参数）。 */
    private List<JsonNode> doneOf(String userId, String definitionKey, String status) throws Exception {
        StringBuilder url = new StringBuilder(TASKS_URL + "/done?page=1&size=50");
        if (definitionKey != null) {
            url.append("&definitionKey=").append(definitionKey);
        }
        if (status != null) {
            url.append("&status=").append(status);
        }
        JsonNode resp = JSON.readTree(performAs(userId, "default", Set.of("super_admin"),
                get(url.toString())).getResponse().getContentAsString(StandardCharsets.UTF_8));
        return ok(resp, "done 查询应成功").path("data") == null ? List.of() : dataItems(resp);
    }

    /** initiated 列表（我发起）。 */
    private List<JsonNode> initiatedOf(String userId) throws Exception {
        JsonNode resp = JSON.readTree(performAs(userId, "default", Set.of("super_admin"),
                get(INSTANCES_URL + "/initiated?page=1&size=50")).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        return ok(resp, "initiated 查询应成功").path("data") == null ? List.of() : dataItems(resp);
    }

    /**
     * 任务操作（complete/reject；办理面动作须以办理人本人发起——超管非 assignee 会被
     * 办理权限校验拒（platform-workflow-03-0003），契约「固定 assignee」口径）。
     */
    private JsonNode taskOp(String taskId, String op, ObjectNode payload) throws Exception {
        return JSON.readTree(performAs(ASSIGNEE, "default", Set.of("super_admin"),
                post(TASKS_URL + "/" + taskId + "/" + op)
                        .contentType(JSON_TYPE)
                        .content(payload == null ? "{}" : JSON.writeValueAsString(payload)))
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void completeTask(String taskId) throws Exception {
        ok(taskOp(taskId, "complete", null), "完成任务应成功");
    }

    private void claimTask(String userId, String taskId) throws Exception {
        ok(JSON.readTree(performAs(userId, "default", Set.of("super_admin"),
                post(TASKS_URL + "/" + taskId + "/claim").contentType(JSON_TYPE).content("{}"))
                .getResponse().getContentAsString(StandardCharsets.UTF_8)), "认领应成功");
    }

    /** 投影四字段断言（definitionKey/definitionName/businessKey/initiator）。 */
    private void assertProjection(JsonNode item, String desc, String definitionKey, String definitionName,
                                  String businessKey, String initiator) {
        assertThat(item.path("definitionKey").asText(null))
                .as(desc + " definitionKey 回显").isEqualTo(definitionKey);
        assertThat(item.path("definitionName").asText(null))
                .as(desc + " definitionName 回显").isEqualTo(definitionName);
        assertThat(item.path("businessKey").asText(null))
                .as(desc + " businessKey 回显").isEqualTo(businessKey);
        assertThat(item.path("initiator").asText(null))
                .as(desc + " initiator 回显").isEqualTo(initiator);
    }

    // ---------- AC-11：投影四字段全量回显（todo/done/initiated 全路径） ----------

    @Test
    void should_return_projection_fields_when_todo_listed() throws Exception {
        String key = uniq("todo-proj");
        String name = key + "-展示名";
        String bk = uniq("todo-bk");
        long defId = createAndDeploy(key, name, SINGLE_TASK_BODY);
        startInstanceAs("1", defId, bk);

        List<JsonNode> items = todoOf(ASSIGNEE, null, null);
        assertThat(items).as("固定 assignee todo 应见 CREATED 任务").hasSize(1);
        JsonNode item = items.get(0);
        // wire id 一律字符串（确认⑤定义域先例）：任务 id 与 instanceId wire 节点应为文本型且非空
        assertThat(item.path("id").isTextual() && !item.path("id").asText("").isEmpty())
                .as("TaskView.id wire 应为字符串").isTrue();
        assertThat(item.path("instanceId").isTextual() && !item.path("instanceId").asText("").isEmpty())
                .as("TaskView.instanceId wire 应为字符串").isTrue();
        assertThat(item.path("status").asText()).as("固定 assignee 任务先 CREATED").isEqualTo("CREATED");
        assertProjection(item, "todo 行", key, name, bk, "1");
    }

    @Test
    void should_return_projection_fields_when_done_listed() throws Exception {
        String key = uniq("done-proj");
        String name = key + "-展示名";
        String bk = uniq("done-bk");
        long defId = createAndDeploy(key, name, TWO_TASK_BODY);
        long instanceId = startInstanceAs("1", defId, bk);
        // 完成 t1 → done 出现 COMPLETED 行
        Long t1 = jdbcTemplate.queryForObject(
                "select id from wf_task where instance_id = ? and node_id = 't1' and status = 'CREATED'",
                Long.class, instanceId);
        completeTask(String.valueOf(t1));

        List<JsonNode> items = doneOf(ASSIGNEE, null, null);
        JsonNode row = items.stream()
                .filter(it -> key.equals(it.path("definitionKey").asText(null))
                        && "t1".equals(it.path("nodeId").asText(null)))
                .findFirst().orElse(null);
        assertThat(row).as("done 应含 t1 已完成行").isNotNull();
        assertThat(row.path("status").asText()).as("t1 应 COMPLETED").isEqualTo("COMPLETED");
        assertThat(row.path("id").isTextual() && !row.path("id").asText("").isEmpty())
                .as("done 行 id wire 应为字符串").isTrue();
        assertProjection(row, "done 行", key, name, bk, "1");
    }

    @Test
    void should_return_projection_fields_when_initiated_listed() throws Exception {
        String key = uniq("init-proj");
        String name = key + "-展示名";
        String bk = uniq("init-bk");
        long defId = createAndDeploy(key, name, SINGLE_TASK_BODY);
        startInstanceAs("1003", defId, bk);

        List<JsonNode> items = initiatedOf("1003");
        JsonNode row = items.stream()
                .filter(it -> bk.equals(it.path("businessKey").asText(null)))
                .findFirst().orElse(null);
        assertThat(row).as("initiated 应含我发起实例").isNotNull();
        assertThat(row.path("id").isTextual() && !row.path("id").asText("").isEmpty())
                .as("实例 id wire 应为字符串").isTrue();
        // 确认⑤/AC-11：initiated 列表同样回显投影四字段（definitionName 为本次扩展新增）
        assertProjection(row, "initiated 行", key, name, bk, "1003");
    }

    // ---------- AC-11：todo/done definitionKey/status 过滤参数 ----------

    @Test
    void should_filter_todo_by_definition_key_when_param_passed() throws Exception {
        String keyA = uniq("flt-a");
        String keyB = uniq("flt-b");
        long defA = createAndDeploy(keyA, keyA + "-名A", SINGLE_TASK_BODY);
        long defB = createAndDeploy(keyB, keyB + "-名B", SINGLE_TASK_BODY);
        startInstanceAs("1", defA, uniq("flt-bk-a"));
        startInstanceAs("1", defB, uniq("flt-bk-b"));

        List<JsonNode> filtered = todoOf(ASSIGNEE, keyA, null);
        assertThat(filtered).as("definitionKey 过滤应仅剩 A 定义任务").hasSize(1);
        assertThat(filtered.get(0).path("definitionKey").asText())
                .as("过滤行 definitionKey 应=查询值").isEqualTo(keyA);

        List<JsonNode> unfiltered = todoOf(ASSIGNEE, null, null);
        assertThat(unfiltered).as("缺省不过滤应两行齐见").hasSize(2);
    }

    @Test
    void should_filter_todo_by_status_when_claimed_or_created() throws Exception {
        String key = uniq("flt-st");
        long defId = createAndDeploy(key, key + "-名", SINGLE_TASK_BODY);
        startInstanceAs("1", defId, uniq("flt-bk-1"));
        startInstanceAs("1", defId, uniq("flt-bk-2"));

        List<JsonNode> created = todoOf(ASSIGNEE, null, "CREATED");
        assertThat(created).as("两任务均 CREATED").hasSize(2);

        // 认领其一 → status 过滤分离两态
        String claimedId = created.get(0).path("id").asText();
        claimTask(ASSIGNEE, claimedId);

        List<JsonNode> claimed = todoOf(ASSIGNEE, null, "CLAIMED");
        assertThat(claimed).as("status=CLAIMED 应仅认领行").hasSize(1);
        assertThat(claimed.get(0).path("id").asText()).isEqualTo(claimedId);

        List<JsonNode> stillCreated = todoOf(ASSIGNEE, null, "CREATED");
        assertThat(stillCreated).as("status=CREATED 应排除已认领行").hasSize(1);
        assertThat(stillCreated.get(0).path("id").asText()).isNotEqualTo(claimedId);
    }

    @Test
    void should_filter_done_by_definition_key_and_status_when_params_passed() throws Exception {
        String key = uniq("done-flt");
        String otherKey = uniq("done-other");
        long defId = createAndDeploy(key, key + "-名", TWO_TASK_BODY);
        long otherDefId = createAndDeploy(otherKey, otherKey + "-名", SINGLE_TASK_BODY);
        long instanceId = startInstanceAs("1", defId, uniq("done-bk"));
        startInstanceAs("1", otherDefId, uniq("done-bk-o"));

        // t1 完成（COMPLETED）→ t2 驳回（REJECTED，指定回退 t1）
        Long t1 = jdbcTemplate.queryForObject(
                "select id from wf_task where instance_id = ? and node_id = 't1' and status = 'CREATED'",
                Long.class, instanceId);
        completeTask(String.valueOf(t1));
        Long t2 = jdbcTemplate.queryForObject(
                "select id from wf_task where instance_id = ? and node_id = 't2' and status = 'CREATED' order by id desc",
                Long.class, instanceId);
        ObjectNode rejectBody = bodyNode();
        rejectBody.put("targetNodeId", "t1");
        rejectBody.put("comment", "退回一级");
        ok(taskOp(String.valueOf(t2), "reject", rejectBody), "t2 指定回退驳回应成功");

        // definitionKey 过滤：done 仅剩 key 定义行（排除 other 定义）
        List<JsonNode> byKey = doneOf(ASSIGNEE, key, null);
        assertThat(byKey).as("definitionKey 过滤应仅 key 定义行").isNotEmpty();
        assertThat(byKey).allSatisfy(it ->
                assertThat(it.path("definitionKey").asText()).isEqualTo(key));

        // status=COMPLETED 过滤：t2（REJECTED）不得混入
        List<JsonNode> completed = doneOf(ASSIGNEE, null, "COMPLETED");
        assertThat(completed).as("status=COMPLETED 应含 t1 行").isNotEmpty();
        assertThat(completed).allSatisfy(it ->
                assertThat(it.path("status").asText()).isEqualTo("COMPLETED"));
        assertThat(completed.stream().noneMatch(it -> "t2".equals(it.path("nodeId").asText(null))))
                .as("status=COMPLETED 过滤不应含被驳回 t2").isTrue();

        // REJECTED 任务入 done（缺省未钉死范围）时必须可经 status=REJECTED 精确分离
        List<JsonNode> unfiltered = doneOf(ASSIGNEE, null, null);
        boolean rejectedVisibleInDone = unfiltered.stream()
                .anyMatch(it -> "t2".equals(it.path("nodeId").asText(null))
                        && "REJECTED".equals(it.path("status").asText(null)));
        if (rejectedVisibleInDone) {
            List<JsonNode> rejected = doneOf(ASSIGNEE, null, "REJECTED");
            assertThat(rejected.stream().anyMatch(it -> "t2".equals(it.path("nodeId").asText(null))))
                    .as("REJECTED 行入 done 时 status=REJECTED 过滤应可见").isTrue();
        }
    }

    // ---------- AC-11：一次查询装配（N+1 黑盒上界） ----------

    @Test
    void should_not_scale_queries_with_row_count_when_projection_assembled() throws Exception {
        String key = uniq("n1");
        long defId = createAndDeploy(key, key + "-名", SINGLE_TASK_BODY);
        int rows = 8;
        for (int i = 0; i < rows; i++) {
            startInstanceAs("1", defId, uniq("n1-bk-" + i));
        }

        SQL_STATEMENTS.set(0);
        List<JsonNode> items = todoOf(ASSIGNEE, null, null);
        assertThat(items).as("8 实例行集应单页齐见").hasSize(rows);
        // 投影四字段已随行回显（无 per-row 补查的数据缺失前提）
        assertThat(items).allSatisfy(it ->
                assertThat(it.path("definitionKey").asText()).isEqualTo(key));
        // 语句数上界常数化：合法实现≈分页 count+select（≤2），放行冗余至 6；
        // per-row JOIN（+8 起步）必然超界（确认⑤「一次查询装配禁 N+1」黑盒 oracle）
        int statements = SQL_STATEMENTS.get();
        assertThat(statements)
                .as("todo 单次列表 SQL 语句数应与行数无关（实测=%d，行数=%d）", statements, rows)
                .isLessThanOrEqualTo(6);
    }

    // ---------- SQL 语句计数支撑 ----------

    /** DataSource 包装：getConnection 返回计数代理（仅计数 prepareStatement/createStatement/prepareCall）。 */
    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger counter;

        CountingDataSource(DataSource delegate, AtomicInteger counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    CountingDataSource.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("prepareStatement".equals(name) || "createStatement".equals(name)
                                || "prepareCall".equals(name)) {
                            counter.incrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            throw ex.getCause() instanceof SQLException sqlEx ? sqlEx : ex;
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
