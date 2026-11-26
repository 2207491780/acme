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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261125-230807（AC-12）根角色 parentId wire 归一 null 测试锚点（Testcontainers PG，装配口径照抄
 * {@code REQ20261122233426TenantInitTemplateTesterTest}；无 Docker 自动跳过——判定口径：
 * Tests run: N, Skipped: 0）。
 *
 * <p>契约（AC-12 + 变更清单「EntityViews 角色视图 parentId 0→null 归一 + 测试锚点」+ 钉死事实⑤）：
 * given 查询角色（平铺/树形）when 响应返回 then 根角色 wire {@code parentId=null}
 * （0 归一 null，对齐 REQ-20261124-213008 §API）；非根角色 parentId 保持实体原值不归一。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-3 角色列表/树 wire path 未在本契约 API 节钉死：按既有 roles/{id}/data-scope、
 *       roles/{id}/menus 测试先例推导 GET /admin/v1/system/roles（分页 page/size query）与
 *       GET /admin/v1/system/roles/tree；404 时按实现披露调整（用例缺陷自查项）。</li>
 *   <li>AMB-4 归一断言锚：根角色判据=「roleCode=super_admin（V2 种子 role_id=1，parentId=0）」+
 *       「夹具新建根角色（创建 parentId 缺省）」双锚；同时断言非根角色 parentId 不受归一影响
 *       （防过度归一把父子关系抹平）。</li>
 *   <li>AMB-5 wire null 形态：断言 parentId 字段存在且为 JSON null（缺失节点视为实现披露缺口红灯锚，
 *       与 SystemApiContractTest「footerCopyright:null 显式序列化」先例同口径）。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：现 wire 根角色 parentId=0（背景 F7），根归一断言失败=实现缺口
 * 锚点；非根角色断言现状即应通过（回归锚）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=Req230807Delta3SystemTestHmacSecret48Bfix000pad0"
})
class REQ20261125230807RoleParentIdWireIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ROLES_URL = "/admin/v1/system/roles";

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
        // 共享环境脏数据 teardown：夹具角色按前缀物理清理（先旁表后主表），并清上下文线程残留
        jdbcTemplate.update("delete from sys_role_menu where tenant_id = 'default' and role_id in "
                + "(select id from sys_role where role_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id = 'default' and role_id in "
                + "(select id from sys_role where role_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id = 'default' and role_id in "
                + "(select id from sys_role where role_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id = 'default' and role_code like ?",
                FIXTURE_PREFIX + "%");
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-12：平铺页根角色 parentId wire=null（0 归一 null） ----------

    @Test
    void should_normalize_root_parent_id_to_null_when_role_page_returned() throws Exception {
        String rootCode = FIXTURE_PREFIX + "-root-" + seq();
        createRole(rootCode, null);

        JsonNode body = callAsSuperAdmin(get(ROLES_URL)
                .queryParam("page", "1").queryParam("size", "200"));
        assertThat(body.path("success").asBoolean()).as("角色分页应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");

        JsonNode records = body.path("data").path("records");
        assertThat(records.isArray()).as("分页 records 应为数组").isTrue();

        // 锚一：V2 种子根角色 super_admin（parentId=0）→ wire null
        JsonNode superAdmin = recordByRoleCode(records, "super_admin");
        assertThat(superAdmin).as("分页应含种子根角色 super_admin").isNotNull();
        assertThat(superAdmin.path("parentId").isMissingNode())
                .as("根角色 parentId 应显式序列化为 null（缺失=实现披露缺口，AMB-5）").isFalse();
        assertThat(superAdmin.path("parentId").isNull())
                .as("根角色 wire parentId=null（0 归一 null，AC-12）").isTrue();

        // 锚二：夹具新建根角色（创建 parentId 缺省）→ wire null
        JsonNode fixtureRoot = recordByRoleCode(records, rootCode);
        assertThat(fixtureRoot).as("分页应含夹具根角色").isNotNull();
        assertThat(fixtureRoot.path("parentId").isMissingNode()).isFalse();
        assertThat(fixtureRoot.path("parentId").isNull())
                .as("夹具根角色 wire parentId=null（0 归一 null，AC-12）").isTrue();
    }

    // ---------- AC-12：非根角色 parentId 不受归一影响（防过度归一） ----------

    @Test
    void should_keep_entity_parent_id_when_role_has_parent() throws Exception {
        String rootCode = FIXTURE_PREFIX + "-p-root-" + seq();
        long rootId = createRole(rootCode, null);
        String childCode = FIXTURE_PREFIX + "-p-child-" + seq();
        createRole(childCode, rootId);

        JsonNode body = callAsSuperAdmin(get(ROLES_URL)
                .queryParam("page", "1").queryParam("size", "200"));
        JsonNode records = body.path("data").path("records");

        JsonNode child = recordByRoleCode(records, childCode);
        assertThat(child).as("分页应含夹具子角色").isNotNull();
        assertThat(child.path("parentId").isMissingNode()).isFalse();
        assertThat(child.path("parentId").asText())
                .as("非根角色 parentId=实体父 id（字符串 wire），不参与 0→null 归一")
                .isEqualTo(String.valueOf(rootId));
    }

    // ---------- AC-12：树形视图根角色 parentId wire=null ----------

    @Test
    void should_normalize_root_parent_id_to_null_when_role_tree_returned() throws Exception {
        String rootCode = FIXTURE_PREFIX + "-tree-root-" + seq();
        long rootId = createRole(rootCode, null);
        String childCode = FIXTURE_PREFIX + "-tree-child-" + seq();
        createRole(childCode, rootId);

        JsonNode body = callAsSuperAdmin(get(ROLES_URL + "/tree"));
        assertThat(body.path("success").asBoolean()).as("角色树应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");

        JsonNode tree = body.path("data");
        assertThat(tree.isArray()).as("角色树 data 应为数组").isTrue();

        JsonNode fixtureRoot = findByRoleCode(tree, rootCode);
        assertThat(fixtureRoot).as("树应含夹具根角色（顶层节点）").isNotNull();
        assertThat(fixtureRoot.path("parentId").isMissingNode()).isFalse();
        assertThat(fixtureRoot.path("parentId").isNull())
                .as("树形根角色 wire parentId=null（0 归一 null，AC-12）").isTrue();

        JsonNode superAdmin = findByRoleCode(tree, "super_admin");
        assertThat(superAdmin).as("树应含种子根角色 super_admin（顶层节点）").isNotNull();
        assertThat(superAdmin.path("parentId").isMissingNode()).isFalse();
        assertThat(superAdmin.path("parentId").isNull())
                .as("树形种子根角色 wire parentId=null").isTrue();

        JsonNode child = findByRoleCode(fixtureRoot.path("children"), childCode);
        assertThat(child).as("夹具子角色应嵌套于夹具根角色 children").isNotNull();
        assertThat(child.path("parentId").asText()).isEqualTo(String.valueOf(rootId));
    }

    // ---------- 夹具与支撑 ----------

    private static int seq() {
        return ++seq;
    }

    /** 经既有 wire 端点创建角色（parentId null=根；AMB-3 path 先例口径），返回回显 id。 */
    private long createRole(String roleCode, Long parentId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("roleCode", roleCode);
        body.put("roleName", FIXTURE_PREFIX + " 夹具角色");
        if (parentId == null) {
            body.putNull("parentId");
        } else {
            body.put("parentId", String.valueOf(parentId));
        }
        JsonNode result = callAsSuperAdmin(post(ROLES_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("前置：创建夹具角色应成功（既有端点回归锚）：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    private JsonNode recordByRoleCode(JsonNode records, String roleCode) {
        for (JsonNode record : records) {
            if (roleCode.equals(record.path("roleCode").asText(null))) {
                return record;
            }
        }
        return null;
    }

    /** 树/children 内按 roleCode 递归查找。 */
    private JsonNode findByRoleCode(JsonNode node, String roleCode) {
        if (node == null || !(node.isArray() || node.isObject())) {
            return null;
        }
        if (node.isObject()) {
            if (roleCode.equals(node.path("roleCode").asText(null))) {
                return node;
            }
            return findByRoleCode(node.path("children"), roleCode);
        }
        for (JsonNode child : node) {
            JsonNode hit = findByRoleCode(child, roleCode);
            if (hit != null) {
                return hit;
            }
        }
        return null;
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
