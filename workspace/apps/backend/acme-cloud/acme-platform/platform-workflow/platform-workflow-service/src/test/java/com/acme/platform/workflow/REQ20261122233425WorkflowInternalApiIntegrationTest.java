package com.acme.platform.workflow;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.workflow.api.WorkflowErrorCodes;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-233425（BPM-5）workflow internal 面 3 端点集成测试（契约确认③；
 * AC-2/AC-3 承载）。装配口径照 {@code REQ20261121142316WorkflowEngineIntegrationTest}
 * 先例：Testcontainers PG 自起容器 + MockMvc HTTP 面（InternalTokenAuthFilter 显式装配，
 * internal 面不经网关）+ JdbcTemplate 夹具；无 Docker 自动跳过——判定口径：日志须见
 * Tests run: N, Skipped: 0 才算验证。
 *
 * <p>契约口径（contracts/REQ-20261122-233425.md 确认②③）：</p>
 * <ul>
 *   <li>POST /internal/v1/workflow/process-instances（body=definitionId+businessKey+variables+
 *       initiatorUserId，业务身份 sub 透传审计）→ 启动返回 instanceId；</li>
 *   <li>GET /internal/v1/workflow/process-instances/{id} → 详情=实例+变量 Map+活动任务快照
 *       List&lt;TaskView&gt;；</li>
 *   <li>PUT /internal/v1/workflow/process-instances/{id}/variables → 增量合并写（复用
 *       VariableService）；</li>
 *   <li>X-Acme-Token 无令牌 401 / 越权 403 internal 口径同形（AC-2）。</li>
 * </ul>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 internal 调用方令牌 sub=业务服务名（确认② SDK 回调口径同构）：本类以
 *     {@code platform-leave}（business-leave 服务名最自然形态）作合法调用方 sub——若 backend 对
 *     internal 3 端点另设调用方白名单且不含该值，红=白名单集披露差异，TL 裁决后改锚；
 *     （既有 internal 面 InternalServiceTaskController 先例接受任意 sub，无白名单，此假定风险低）；
 * A-2 越权 403 锚=refresh 令牌冒充 access 调用（框架口径 TOKEN_TYPE_MISMATCH→403，
 *     InternalTokenAuthFilterTest.refreshTypeRejected403 同款先例，本类以同 hmac-secret 自签
 *     tokenType=refresh 的 JWS 构造；若 backend 对 internal 面另实现 sub 白名单 403，
 *     机制披露差异 TL 裁决）；
 * A-3 启动响应 instanceId 宽容解析：data.instanceId / data.id / data 裸值（Long wire=字符串
 *     FR-024，Jackson 数值宽容读取）；
 * A-4 详情活动任务快照字段名宽容：data.tasks 优先，退化 data.activeTasks（确认③
 *     「活动任务快照 List&lt;TaskView&gt;」未钉字段名，ProcessInstanceView 先例字段=tasks）；
 * A-5 PUT variables 请求体宽容二形态：{"variables":{...}}（对齐启动请求字段命名）优先，
 *     业务失败则退化裸 Map 形态；两形态皆拒=请求体形状披露差异，TL 裁决；
 * A-6 未知实例 GET/PUT 业务失败信封 code≠0（pitfalls 口径：业务错误勿断言 4xx；
 *     wire 码引用 WorkflowErrorCodes.INSTANCE_NOT_FOUND 常量）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformWorkflowApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-233425-internal-it-secret-0123456789"
})
class REQ20261122233425WorkflowInternalApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 与 @SpringBootTest properties 同值的测试 hmac-secret（自签 refresh 令牌构造用，A-2）。 */
    private static final String HMAC_SECRET = "req-233425-internal-it-secret-0123456789";

    /** 内部令牌 iss（SecurityProperties.Token 缺省 issuer=acme-gateway）。 */
    private static final String ISSUER = "acme-gateway";

    private static final String DEFS_URL = "/admin/v1/workflow/process-definitions";
    private static final String INSTANCES_URL = "/admin/v1/workflow/process-instances";
    private static final String TASKS_URL = "/admin/v1/workflow/tasks";
    private static final String INTERNAL_URL = "/internal/v1/workflow/process-instances";

    private static final MediaType JSON_TYPE = MediaType.APPLICATION_JSON;

    /** 合法调用方 sub=业务服务名（A-1：business-leave 试点服务名自然形态）。 */
    private static final String CALLER_SUB = "platform-leave";

    /** 内部启动透传的业务发起人（确认③ initiatorUserId）。 */
    private static final String INITIATOR_USER_ID = "4201";

    /** 单任务直通夹具（固定 assignee，审批推进/活动任务快照锚）。 */
    private static final String SINGLE_TASK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-only"/>
            <userTask id="task-only" name="唯一任务" acme:assignee="4101"/>
            <sequenceFlow id="f1" sourceRef="task-only" targetRef="end"/>
            <endEvent id="end"/>
            """;

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

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨域权限判定桩（先例：admin 面 Web 待办中心操作走 super_admin 短路）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    /** 跨域身份目录桩（本类不解析候选，空桩即可）。 */
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
        // teardown 清共享脏数据（每测试类独立 PG 容器，本类用例是唯一写入方；叶子表先删防 FK）
        jdbcTemplate.update("delete from wf_timer_job");
        jdbcTemplate.update("delete from wf_history_activity");
        jdbcTemplate.update("delete from wf_variable");
        jdbcTemplate.update("delete from wf_task");
        jdbcTemplate.update("delete from wf_execution");
        jdbcTemplate.update("delete from wf_process_instance");
        jdbcTemplate.update("delete from wf_process_definition");
    }

    // ---------- AC-2：internal 启动端点 ----------

    @Test
    void should_start_instance_and_return_instance_id_when_internal_token_valid() throws Exception {
        String key = uniq("internal-start");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String businessKey = uniq("internal-inst");

        ObjectNode variables = bodyNode();
        variables.put("amount", 800);
        variables.put("reason", "tester-it");
        JsonNode resp = internalCall(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, businessKey, variables))));
        ok(resp, "internal 启动应成功");

        // instanceId 宽容解析（A-3），且实例落库可对账
        String instanceId = instanceIdOf(resp);
        assertThat(instanceId).as("启动应返回 instanceId（A-3 宽容形态）：%s", resp).isNotBlank();
        assertThat(findInstanceId(businessKey)).as("实例应按 businessKey 落库").isNotNull();

        // 业务身份透传审计（确认③ initiatorUserId）：admin 详情 initiator=透传值
        JsonNode detail = ok(callAsSuperAdmin(get(INSTANCES_URL + "/" + instanceId)),
                "admin 实例详情应成功");
        assertThat(detail.path("data").path("initiator").asText(null))
                .as("internal 启动应透传 initiatorUserId 入审计面（确认③）：%s", detail)
                .isEqualTo(INITIATOR_USER_ID);
    }

    @Test
    void should_reject_internal_start_with_401_when_token_missing_or_invalid() throws Exception {
        String key = uniq("internal-401");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));

        // ① 无令牌 → 401（TOKEN_MISSING，框架口径 SYS-02-0001）
        MvcResult missing = mvc.perform(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, uniq("no-token"), null))))
                .andReturn();
        assertThat(missing.getResponse().getStatus())
                .as("无令牌 internal 启动应 401：%s",
                        missing.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(401);
        assertThat(missing.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("401 响应应为失败信封且承载 TOKEN_MISSING wire 码")
                .contains("SYS-02-0001");

        // ② 伪造令牌（验签失败）→ 401（TOKEN_INVALID，SYS-02-0002）
        MvcResult forged = mvc.perform(post(INTERNAL_URL)
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", "forged.token.value")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, uniq("forged"), null))))
                .andReturn();
        assertThat(forged.getResponse().getStatus())
                .as("伪造令牌 internal 启动应 401：%s",
                        forged.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(401);
        assertThat(forged.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("401 响应应承载 TOKEN_INVALID wire 码")
                .contains("SYS-02-0002");

        // 两例都不得产生实例（拒绝在过滤器层，业务零副作用）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_process_instance", Long.class))
                .as("未授权启动不得产生实例").isZero();
    }

    @Test
    void should_reject_internal_start_with_403_when_token_type_not_entitled() throws Exception {
        String key = uniq("internal-403");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));

        // A-2：refresh 令牌冒充 access 调 internal 面（同密钥自签，tokenType=refresh）
        MvcResult result = mvc.perform(post(INTERNAL_URL)
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", refreshToken())
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, uniq("escalated"), null))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("越权（refresh 冒充 access）internal 启动应 403：%s",
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(403);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("403 响应应承载 TOKEN_TYPE_MISMATCH wire 码")
                .contains("SYS-02-0004");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_process_instance", Long.class))
                .as("越权启动不得产生实例").isZero();
    }

    // ---------- AC-3：详情（变量+活动任务快照）与变量增量合并写 ----------

    @Test
    void should_return_detail_with_variables_and_active_task_snapshot_when_get_by_id()
            throws Exception {
        String key = uniq("internal-detail");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String businessKey = uniq("detail-inst");

        ObjectNode variables = bodyNode();
        variables.put("amount", 800);
        variables.put("leaveType", "annual");
        JsonNode started = internalCall(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, businessKey, variables))));
        ok(started, "internal 启动应成功");
        String instanceId = instanceIdOf(started);

        JsonNode detail = internalCall(get(INTERNAL_URL + "/" + instanceId));
        ok(detail, "internal 详情应成功");
        JsonNode data = detail.path("data");

        // 实例形状：businessKey / status 回显（宽容字段面）
        assertThat(data.path("businessKey").asText(null))
                .as("详情应回显 businessKey：%s", data).isEqualTo(businessKey);
        assertThat(data.path("status").asText(null))
                .as("运行中实例状态应为 RUNNING：%s", data).isEqualTo("RUNNING");

        // 变量 Map（确认③：详情含变量）
        JsonNode vars = data.path("variables");
        assertThat(vars.isObject()).as("详情应含变量 Map：%s", data).isTrue();
        assertThat(vars.path("amount").asDouble())
                .as("启动变量应回显（数值宽容读取）").isEqualTo(800.0);
        assertThat(vars.path("leaveType").asText())
                .as("启动变量应回显").isEqualTo("annual");

        // 活动任务快照（确认③：List<TaskView>；A-4 字段名宽容）
        JsonNode tasks = data.has("tasks") ? data.path("tasks")
                : data.path("activeTasks");
        assertThat(tasks.isArray()).as("详情应含活动任务快照数组：%s", data).isTrue();
        assertThat(tasks).as("首个用户任务应出现在活动任务快照")
                .anySatisfy(task -> assertThat(task.path("nodeId").asText())
                        .isEqualTo("task-only"));
    }

    @Test
    void should_merge_variables_incrementally_when_put_variables_called() throws Exception {
        String key = uniq("internal-merge");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String businessKey = uniq("merge-inst");

        ObjectNode variables = bodyNode();
        variables.put("amount", 800);
        variables.put("stage", "init");
        JsonNode started = internalCall(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, businessKey, variables))));
        ok(started, "internal 启动应成功");
        String instanceId = instanceIdOf(started);

        // 增量合并写：新键 stage2 + 覆写 amount，不携带 stage（应保留）
        ObjectNode writeVars = bodyNode();
        writeVars.put("amount", 1200);
        writeVars.put("stage2", "merged");
        JsonNode putResp = putVariables(instanceId, writeVars);
        ok(putResp, "internal 变量增量写应成功（A-5 宽容形态）");

        JsonNode detail = internalCall(get(INTERNAL_URL + "/" + instanceId));
        ok(detail, "internal 详情应成功");
        JsonNode vars = detail.path("data").path("variables");
        assertThat(vars.path("amount").asDouble())
                .as("同键覆写应生效").isEqualTo(1200.0);
        assertThat(vars.path("stage2").asText())
                .as("新键应写入").isEqualTo("merged");
        assertThat(vars.path("stage").asText())
                .as("增量合并语义：未携带键必须保留").isEqualTo("init");

        // DB 对账（wf_variable 库中真实状态，pitfalls ⑥）
        assertThat(variableText(Long.parseLong(instanceId), "stage2")).isNotNull();
        assertThat(variableText(Long.parseLong(instanceId), "amount")).contains("1200");
    }

    @Test
    void should_fail_with_business_envelope_when_instance_unknown_on_get_or_put() throws Exception {
        // 未知实例：业务失败信封 code≠0（A-6：勿断言 4xx），不得 5xx
        JsonNode getResp = internalCall(get(INTERNAL_URL + "/99999999"));
        assertThat(getResp.path("success").asBoolean())
                .as("未知实例 GET 应业务失败：%s", getResp).isFalse();
        assertThat(getResp.path("code").asText())
                .as("未知实例 GET wire 码").isEqualTo(WorkflowErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND);

        JsonNode putResp = putVariables("99999999", bodyNode().put("k", "v"));
        assertThat(putResp.path("success").asBoolean())
                .as("未知实例 PUT 应业务失败：%s", putResp).isFalse();
        assertThat(putResp.path("code").asText())
                .as("未知实例 PUT 业务失败信封 code≠0（pitfalls：勿断言 4xx；wire 码宽容——"
                        + "实现披露 GET=INSTANCE_NOT_FOUND 而 PUT 走通用段 SYS-01-0001，契约未钉 "
                        + "PUT 未知实例分段码，tester 不越契约钉死）")
                .isNotEqualTo("0");
    }

    // ---------- 请求支撑 ----------

    /** internal 启动体（确认③：definitionId+businessKey+variables+initiatorUserId）。 */
    private ObjectNode internalStartBody(long definitionId, String businessKey, ObjectNode variables) {
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(definitionId));
        body.put("businessKey", businessKey);
        body.put("initiatorUserId", INITIATOR_USER_ID);
        if (variables != null) {
            body.set("variables", variables);
        }
        return body;
    }

    /** PUT variables（A-5 宽容二形态：{"variables":{...}} 优先，业务失败退化裸 Map）。 */
    private JsonNode putVariables(String instanceId, ObjectNode variables) throws Exception {
        ObjectNode wrapped = bodyNode();
        wrapped.set("variables", variables);
        JsonNode resp = internalCall(put(INTERNAL_URL + "/" + instanceId + "/variables")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(wrapped)));
        if (!resp.path("success").asBoolean()) {
            resp = internalCall(put(INTERNAL_URL + "/" + instanceId + "/variables")
                    .contentType(JSON_TYPE)
                    .content(JSON.writeValueAsString(variables)));
        }
        return resp;
    }

    /** internal 面 MockMvc 调用（合法调用方 sub=业务服务名，A-1；tenant=default 对齐夹具）。 */
    private JsonNode internalCall(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", internalToken(CALLER_SUB))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "leave-svc", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("internal 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private String internalToken(String sub) {
        return tokenIssuer.issue(new AuthenticatedSubject(sub, "default", Channel.B, Set.of()));
    }

    /** A-2：同密钥自签 tokenType=refresh 的 JWS（claims 集合对齐 7.7：iss/sub/tenantId/channel/
     * roles/tokenType/iat/exp/jti）。 */
    private String refreshToken() {
        SecretKey key = Keys.hmacShaKeyFor(HMAC_SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject("rogue-service")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .claim("tokenType", "refresh")
                .claim("roles", List.of())
                .claim("tenantId", "default")
                .claim("channel", "B")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** A-3：instanceId 宽容解析（data.instanceId / data.id / data 裸值；Long wire=字符串）。 */
    private String instanceIdOf(JsonNode resp) {
        JsonNode data = resp.path("data");
        if (data.has("instanceId")) {
            return data.path("instanceId").asText();
        }
        if (data.has("id")) {
            return data.path("id").asText();
        }
        return data.isValueNode() ? data.asText() : data.toString();
    }

    // ---------- admin 面支撑（照抄先例：部署直达 DEPLOYED） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("admin 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private long createDraft(String key, String xml) throws Exception {
        ObjectNode body = bodyNode();
        body.put("key", key);
        body.put("name", key + "-name");
        if (xml != null) {
            body.put("bpmnXml", xml);
        }
        ok(callAsSuperAdmin(post(DEFS_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "创建草稿应成功");
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_definition where process_key = ? and deploy_version = 1",
                Long.class, key);
        assertThat(ids).as("定义 %s 应已落库", key).isNotEmpty();
        return ids.get(0);
    }

    private void deploy(long definitionId) throws Exception {
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + definitionId + "/deploy")), "部署应成功");
    }

    private long createAndDeploy(String key, String xml) throws Exception {
        long id = createDraft(key, xml);
        deploy(id);
        List<String> statuses = jdbcTemplate.queryForList(
                "select status from wf_process_definition where id = ?", String.class, id);
        assertThat(statuses).as("部署后应 DEPLOYED").containsExactly("DEPLOYED");
        return id;
    }

    // ---------- BPMN / 断言 / DB 支撑（照抄先例口径） ----------

    private static String bpmn(String processId, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:acme="http://acme.dev/bpmn/ext/v1"
                             targetNamespace="http://acme.dev/bpmn/it">
                  <process id="%s" name="%s">
                %s
                  </process>
                </definitions>
                """.formatted(processId, processId, body);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    private String uniq(String suffix) {
        return "wf233425" + SEQ.incrementAndGet() + suffix;
    }

    private ObjectNode bodyNode() {
        return JSON.createObjectNode();
    }

    /** 变量值（jsonb ::text 投影），空缺返回 null（先例口径）。 */
    private String variableText(long instanceId, String name) {
        List<String> rows = jdbcTemplate.queryForList(
                "select value::text from wf_variable where instance_id = ? and name = ?",
                String.class, instanceId, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long findInstanceId(String businessKey) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_instance where business_key = ? order by id desc",
                Long.class, businessKey);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
