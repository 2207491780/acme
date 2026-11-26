package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261125-230807（AC-22）租户开通初始化 templateCode 必填化后端校验测试（Testcontainers PG，
 * 装配口径照抄 {@code REQ20261122233426TenantInitTemplateTesterTest}；无 Docker 自动跳过——判定口径：
 * Tests run: N, Skipped: 0）。
 *
 * <p>契约（确认记录③ + AC-22 + 钉死事实⑥）：新建租户模板必选；后端
 * {@code AdminTenantInitRequest.templateCode} 必填校验（blank → PARAM_INVALID 族既有码，空提交被后端
 * 拦截）；tenantCreate 端点保留但前端创建分支不再调用（tenantInit 提交必走）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-14 blank 判定覆盖两形态：templateCode 空串（""）与字段缺省（null）均属「空提交」，
 *       后端都应拒绝（校验实现形态未知，双锚覆盖）。</li>
 *   <li>AMB-15 PARAM_INVALID 族=业务拒绝统一口径：断言 Result.code≠0（HTTP 200 业务信封），不钉
 *       字面量（历轮 tester 口径；具体码字面量由实现既有枚举承载）。</li>
 *   <li>AMB-16 init wire path/请求体照 REQ-233426 先例：POST /admin/v1/system/tenants/init，
 *       body {tenantCode, tenantName, remark, adminUsername, adminNickname, parentId}。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：现实现无 templateCode 必填校验（字段不存在，空值开通成功）→
 * assertRejected 失败=实现缺口锚点；两用例的「拒绝后不落租户」断言同样红（开通已执行）。
 * teardown 按前缀物理清理开通产物（租户/管理员/角色/绑定/配置/配额），兼容红绿两态。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=Req230807Delta3SystemTestHmacSecret48Bfix000pad0"
})
class REQ20261125230807TenantInitTemplateRequiredIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANTS_INIT_URL = "/admin/v1/system/tenants/init";

    private static final String FIXTURE_PREFIX = "req230807";

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
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    private static int seq;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        seq = 0;
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：开通产物按前缀物理清理（先配置/配额/绑定旁表再主表）——
        // TenantInitTemplateTesterTest 先例口径；兼容红态（开通已执行）与绿态（校验拦截无落库）
        jdbcTemplate.update("delete from sys_config where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_quota where tenant_id in "
                + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-22：templateCode 空串 → PARAM_INVALID 族拒绝，且不落租户 ----------

    @Test
    void should_reject_tenant_init_when_template_code_blank() throws Exception {
        String tenantCode = FIXTURE_PREFIX + "-blank-tpl-" + nextSeq();
        ObjectNode body = initBody(tenantCode);
        body.put("templateCode", "");

        JsonNode response = callAsSuperAdmin(post(TENANTS_INIT_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertRejected(response, "templateCode 空串开通");

        // 拒绝即拦截：不落任何开通产物（租户/管理员均不存在）
        assertThat(queryTenantCount(tenantCode))
                .as("校验拒绝后不应落租户（双重拦截后端面）").isZero();
    }

    // ---------- AC-22：templateCode 字段缺省（null）→ PARAM_INVALID 族拒绝，且不落租户 ----------

    @Test
    void should_reject_tenant_init_when_template_code_absent() throws Exception {
        String tenantCode = FIXTURE_PREFIX + "-absent-tpl-" + nextSeq();
        ObjectNode body = initBody(tenantCode);

        JsonNode response = callAsSuperAdmin(post(TENANTS_INIT_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertRejected(response, "templateCode 缺省开通");

        assertThat(queryTenantCount(tenantCode))
                .as("校验拒绝后不应落租户（双重拦截后端面）").isZero();
    }

    // ---------- 夹具与支撑 ----------

    /** init 请求体（REQ-233426 先例形状；templateCode 由用例按 AMB-14 两形态构造）。 */
    private static ObjectNode initBody(String tenantCode) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 模板必填夹具租户");
        body.putNull("remark");
        body.put("adminUsername", "tplreq" + nextSeq() + "admin");
        body.put("adminNickname", tenantCode + " 管理员");
        body.putNull("parentId");
        return body;
    }

    private long queryTenantCount(String tenantCode) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where tenant_code = ?", Long.class, tenantCode);
        return count == null ? -1L : count;
    }

    private void assertRejected(JsonNode body, String what) {
        assertThat(body.path("success").asBoolean()).as(what + " 应被拒绝：%s", body).isFalse();
        assertThat(body.path("code").asText())
                .as(what + " 业务错误=统一响应体 Result.code≠0（PARAM_INVALID 族，AMB-15）")
                .isNotEqualTo("0");
    }

    private static int nextSeq() {
        return ++seq;
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
