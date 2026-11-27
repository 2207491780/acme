package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120104 岗位管理（platform-system）服务域集成测试（Testcontainers PG，装配口径同
 * {@code REQ20261114120104DeptApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-2 岗位增删改查（分页）、被用户引用删除 → {@code POSITION_IN_USE}、无引用删除成功；
 * AC-7 岗位端点无权限统一拒绝。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}；契约仅给符号码
 *       {@code POSITION_IN_USE}，wire 三段式码不可知（禁读实现、禁猜测）：守卫用例断言 code≠0 且
 *       ≠权限拒绝码 {@code platform-system-07-0001}，精确 wire 码待 backend 落码后回填——AMB-1。</li>
 *   <li>岗位字段命名未指明（名称/编码/状态）：请求体 {@code positionName}/{@code name}、
 *       {@code positionCode}/{@code code} 双键兼容发送，响应按 id 定位——AMB-2。</li>
 *   <li>GET 分页响应形态假定 data.records/total（PageResult 惯例）——AMB-3。</li>
 *   <li>PUT 是否携带乐观锁 version 未指明（工程惯例携带）：列表/详情节点含 version 则回传——AMB-4。</li>
 *   <li>删除是否软删未指明（审计基类惯例软删）：成功删除断言 deleted=true；若物理删按实际行为对齐——AMB-5。</li>
 *   <li>「被用户引用」判定口径（含软删用户与否）未指明：以存活用户 position_id 绑定触发守卫——AMB-6。</li>
 *   <li>岗位编码唯一性约束未在契约点名：不设重复编码用例——AMB-7。</li>
 *   <li>正向用例以 super_admin 角色令牌驱动；无权限用例以未绑定角色令牌驱动（同范本口径）。</li>
 * </ul>
 */
class REQ20261114120104PositionApiIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PERMISSION_DENIED = "platform-system-07-0001";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user_role where user_id in "
                + "(select id from sys_user where username like 'req120104-%')");
        jdbcTemplate.update("delete from sys_user where username like 'req120104-%'");
        jdbcTemplate.update("delete from sys_position");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-2：增/查（分页）/改 生效 ----------

    @Test
    void should_createListAndUpdatePosition_when_positionCrudWithPermission() throws Exception {
        String id = createPosition("req-120104-position", "req-120104-code", "ENABLED");

        // 分页列表可见（AMB-3：records/total 惯例形态）
        JsonNode page = call(get("/admin/v1/system/positions")
                .param("page", "1").param("size", "50"));
        assertThat(page.path("code").asText()).isEqualTo("0");
        assertThat(containsRecord(page.path("data").path("records"), "id", id))
                .as("新建岗位应出现在分页列表").isTrue();

        // 编辑（version 惯例：记录含则回传，AMB-4）
        JsonNode record = findRecord(page.path("data").path("records"), "id", id);
        JsonNode updated = call(put("/admin/v1/system/positions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(positionBody("req-120104-position-2",
                        "req-120104-code-2", "ENABLED", versionOf(record)))));
        assertThat(updated.path("code").asText()).isEqualTo("0");

        JsonNode after = call(get("/admin/v1/system/positions")
                .param("page", "1").param("size", "50"));
        JsonNode renamed = findRecord(after.path("data").path("records"), "id", id);
        assertThat(renamed).isNotNull();
        assertThat(nameOf(renamed)).as("PUT 后分页列表应反映新名称").isEqualTo("req-120104-position-2");
    }

    // ---------- AC-2 边界：被用户引用的岗位删除 → POSITION_IN_USE ----------

    @Test
    void should_rejectDeleteWithGuardCode_when_deletePositionReferencedByUser() throws Exception {
        String positionId = createPosition("req-120104-inuse", "req-120104-inuse-code", "ENABLED");
        long boundUserId = seedPositionUser("req120104-position-user", Long.parseLong(positionId));

        JsonNode rejected = call(delete("/admin/v1/system/positions/{id}", positionId));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("被引用岗位删除应业务失败（≠0）且非权限拒绝（AMB-1）")
                .isNotEqualTo("0").isNotEqualTo(PERMISSION_DENIED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_position where id=?", Long.class, Long.parseLong(positionId)))
                .isEqualTo(1L);
        // 用户岗位绑定不受影响
        assertThat(jdbcTemplate.queryForObject(
                "select position_id from sys_user where id=?", Long.class, boundUserId))
                .as("被守卫拒绝后用户 position 绑定应保持").isEqualTo(Long.parseLong(positionId));
    }

    // ---------- AC-2：无引用岗位删除成功 ----------

    @Test
    void should_deletePosition_when_noUserReference() throws Exception {
        String id = createPosition("req-120104-free", "req-120104-free-code", "ENABLED");

        JsonNode deleted = call(delete("/admin/v1/system/positions/{id}", id));

        assertThat(deleted.path("code").asText()).isEqualTo("0");
        // AMB-5：软删口径（审计基类惯例）
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from sys_position where id=?", Boolean.class, Long.parseLong(id))).isTrue();
    }

    // ---------- AC-2 边界：不存在岗位 update/delete → 统一失败 ----------

    @Test
    void should_failWithBusinessCode_when_updateOrDeleteMissingPosition() throws Exception {
        JsonNode deleted = call(delete("/admin/v1/system/positions/{id}", "99999999"));
        assertThat(deleted.path("success").asBoolean()).isFalse();
        assertThat(deleted.path("code").asText()).isNotEqualTo("0");

        JsonNode updated = call(put("/admin/v1/system/positions/{id}", "99999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(positionBody("x", "y", "ENABLED", null))));
        assertThat(updated.path("success").asBoolean()).isFalse();
        assertThat(updated.path("code").asText()).isNotEqualTo("0");
    }

    // ---------- AC-7：岗位端点无权限 → 统一拒绝 ----------

    @Test
    void should_rejectAllPositionEndpoints_when_noPermission() throws Exception {
        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                post("/admin/v1/system/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(positionBody("x", "y", "ENABLED", null))),
                get("/admin/v1/system/positions"),
                put("/admin/v1/system/positions/{id}", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(positionBody("x", "y", "ENABLED", null))),
                delete("/admin/v1/system/positions/{id}", "1"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = callWithNoPermission(endpoint);
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint.toString()).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint.toString())
                    .isNotEqualTo("0");
        }
    }

    // ---------- 支撑 ----------

    private String createPosition(String name, String code, String status) {
        try {
            JsonNode body = call(post("/admin/v1/system/positions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(positionBody(name, code, status, null))));
            assertThat(body.path("code").asText()).as("新建岗位应成功").isEqualTo("0");
            return body.path("data").path("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("新建岗位请求失败", ex);
        }
    }

    /**
     * 岗位请求体：契约只给「名称/编码/状态」语义未给字段名（AMB-2）——positionName/name、
     * positionCode/code 双键兼容发送；version 惯例（AMB-4）由调用方决定是否携带。
     */
    private static Map<String, Object> positionBody(String name, String code, String status, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionName", name);
        body.put("name", name);
        body.put("positionCode", code);
        body.put("code", code);
        body.put("status", status);
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    /** 预置绑定岗位的存活用户（position_id 列为契约 V9 明示变更）。 */
    private long seedPositionUser(String username, long positionId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false, null, ?,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                System.identityHashCode(username) & 0x7FFFFFFF | 0x40000000L, username, username, positionId);
        Long id = jdbcTemplate.queryForObject(
                "select id from sys_user where username=?", Long.class, username);
        return id == null ? -1L : id;
    }

    private JsonNode callWithNoPermission(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, Set.of("req120104-unbound-role"));
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, Set.of("super_admin"));
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request, Set<String> roles) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static boolean containsRecord(JsonNode records, String field, String value) {
        return findRecord(records, field, value) != null;
    }

    private static JsonNode findRecord(JsonNode records, String field, String value) {
        for (JsonNode record : records) {
            if (value.equals(record.path(field).asText())) {
                return record;
            }
        }
        return null;
    }

    private static Object versionOf(JsonNode record) {
        return record != null && record.hasNonNull("version") ? record.get("version") : null;
    }

    /** 岗位名称字段兼容读取（positionName 优先，AMB-2）。 */
    private static String nameOf(JsonNode node) {
        if (node.hasNonNull("positionName")) {
            return node.get("positionName").asText();
        }
        return node.hasNonNull("name") ? node.get("name").asText() : null;
    }
}
