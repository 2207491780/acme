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
import com.acme.platform.workflow.api.WorkflowErrorCodes;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-023441 BPM-2——layoutJson 扩展集成测试（AC-14；专项 2 例，
 * 既有 49 例零回归由 backend run2 全量轮承载）。
 *
 * 契约口径（contracts/REQ-20261122-023441.md 确认⑤ + backend 取舍已定）：
 *   - PUT /{id}/xml 请求体（ProcessDefinitionXmlSaveRequest）加可选 layoutJson 字段，
 *     随 XML 整体覆盖落库 layout_json 列（单事务「XML+布局一起保存」）；
 *   - GET /{id} 详情回显 layoutJson；
 *   - 保存缺省 layoutJson（字段缺省/null）→ layout_json 置 NULL（随 XML 整体覆盖，不保留旧值）。
 *
 * 装配照 {@code REQ20261121142316WorkflowEngineIntegrationTest} 先例（Testcontainers PG 自起容器 +
 * MockMvc HTTP 面 + JdbcTemplate 夹具；无 ServiceTask 委托/调度联动——本专项不涉及，省内嵌桩）；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证（pitfall 16）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformWorkflowApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-bpm2-tester-it-secret-0123456789"
})
class REQ20261122023441WorkflowLayoutJsonIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEFS_URL = "/admin/v1/workflow/process-definitions";

    private static final MediaType JSON_TYPE = MediaType.APPLICATION_JSON;

    /** 布局夹具（设计器 saveXml 载荷形态：nodes 数组 + 坐标）。 */
    private static final String LAYOUT_JSON =
            "{\"nodes\":[{\"id\":\"start\",\"x\":60,\"y\":80},"
                    + "{\"id\":\"task-1\",\"x\":250,\"y\":80},"
                    + "{\"id\":\"end\",\"x\":440,\"y\":80}]}";

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

    /** 跨域权限判定桩（SchedulerCore 先例：判定走 platform-system Feign 契约，单服务测试域
     * 以 MockitoBean 桩件承载——缺省放行）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    /** 跨域身份目录桩（REQ-142316 契约 WorkflowIdentityClient：本专项无实例流，仅补齐 bean 面）。 */
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
        // teardown 清共享脏数据（每测试类独立 PG 容器，本类用例是唯一写入方）
        jdbcTemplate.update("delete from wf_process_definition");
    }

    // ---------- 夹具 ----------

    /** 最小合法 BPMN XML（start → userTask → end，保存期/部署期校验均可通过形态）。 */
    private static String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:acme="http://acme.dev/bpmn/ext/v1"
                             targetNamespace="http://acme.dev/bpmn/it">
                  <process id="proc-bpm2-it">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="task-1"/>
                    <userTask id="task-1" name="审批" acme:assignee="1001"/>
                    <sequenceFlow id="f1" sourceRef="task-1" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;
    }

    private String uniq(String suffix) {
        return "bpm2it" + SEQ.incrementAndGet() + suffix;
    }

    private ObjectNode bodyNode() {
        return JSON.createObjectNode();
    }

    /** 平台管理操作者（种子超管 userId=1 / default 租户 / super_admin 短路全量权限点）。 */
    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
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

    /** 创建草稿（无 XML）。 */
    private long createDraft(String key, String name) throws Exception {
        ObjectNode body = bodyNode();
        body.put("key", key);
        body.put("name", name);
        ok(callAsSuperAdmin(post(DEFS_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "创建草稿应成功");
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_definition where process_key = ?", Long.class, key);
        assertThat(ids).as("定义 %s 应已落库", key).isNotEmpty();
        return ids.get(0);
    }

    /** 保存 XML（可携 layoutJson；layoutJson=null=字段缺省形态）。 */
    private void saveXml(long definitionId, String xml, String layoutJson) throws Exception {
        ObjectNode body = bodyNode();
        body.put("bpmnXml", xml);
        if (layoutJson != null) {
            body.put("layoutJson", layoutJson);
        }
        ok(callAsSuperAdmin(put(DEFS_URL + "/" + definitionId + "/xml")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "保存 XML 应成功");
    }

    /** GET 详情 data 视图。 */
    private JsonNode detail(long definitionId) throws Exception {
        return ok(callAsSuperAdmin(get(DEFS_URL + "/" + definitionId)), "定义详情应成功").path("data");
    }

    // ---------- AC-14 ----------

    @Test
    void should_echo_layout_json_when_xml_saved_with_layout() throws Exception {
        long defId = createDraft(uniq("echo"), "布局回显流程");

        // given: 保存含 layoutJson 的 XML（AC-14：保存含布局）
        saveXml(defId, bpmn(), LAYOUT_JSON);

        // then: GET 详情回显 layoutJson（jsonb 列规范化文本——语义等价断言）
        JsonNode echoed = detail(defId).path("layoutJson");
        assertThat(echoed.isMissingNode() || echoed.isNull())
                .as("含布局保存后 GET 应回显 layoutJson（不应缺失/为空）：%s", echoed)
                .isFalse();
        assertThat(echoed.asText())
                .as("回显 layoutJson 应与载荷语义等价")
                .satisfies(text -> assertThat(JSON.readTree(text)).isEqualTo(JSON.readTree(LAYOUT_JSON)));

        // and: 落库面一致（layout_json 列随 XML 同事务覆盖；jsonb 规范化后语义等价）
        String stored = jdbcTemplate.queryForObject(
                "select layout_json::text from wf_process_definition where id = ?", String.class, defId);
        assertThat(stored)
                .as("layout_json 列应落布局原文（语义等价）")
                .satisfies(text -> assertThat(JSON.readTree(text)).isEqualTo(JSON.readTree(LAYOUT_JSON)));
    }

    @Test
    void should_reset_layout_json_to_null_when_xml_saved_without_layout() throws Exception {
        long defId = createDraft(uniq("reset"), "缺省布局覆盖流程");

        // given: 先保存含布局（回显基线；jsonb 规范化→语义等价断言）
        saveXml(defId, bpmn(), LAYOUT_JSON);
        assertThat(JSON.readTree(detail(defId).path("layoutJson").asText()))
                .as("前置：含布局保存应回显").isEqualTo(JSON.readTree(LAYOUT_JSON));

        // when: 再次保存缺省 layoutJson（字段缺省/null）
        saveXml(defId, bpmn().replace("proc-bpm2-it", "proc-bpm2-it-v2"), null);

        // then: 随 XML 整体覆盖置 NULL（不保留旧布局；backend 取舍已定口径）
        JsonNode echoed = detail(defId).path("layoutJson");
        assertThat(echoed.isMissingNode() || echoed.isNull() || echoed.asText().isEmpty())
                .as("缺省 layoutJson 保存后 GET 不应回显旧布局：%s", echoed)
                .isTrue();
        Object stored = jdbcTemplate.queryForObject(
                "select layout_json from wf_process_definition where id = ?", Object.class, defId);
        assertThat(stored).as("layout_json 列应整体覆盖置 NULL").isNull();
    }

    // 错误码常量编译锚（wire 码段位钉死，防漂移；01-0003 面与本类无直接交互）
    @SuppressWarnings("unused")
    private static final String VALIDATION_FAILED = WorkflowErrorCodes.WORKFLOW_VALIDATION_FAILED;
}
