package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.application.PermissionUseCase;
import com.acme.platform.system.domain.SysPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261120-214653 权限点管理筛选（platform-system）集成测试（Testcontainers PG，照抄
 * {@code SystemIntegrationTest}/{@code REQ20261114120104DeptApiIntegrationTest} 装配口径；
 * 本域不触 Redis pub/sub，容器仅 PG；无 Docker 自动跳过）。
 *
 * <p>覆盖 AC-1~9（契约 §API：GET /admin/v1/system/permissions 新增可选 query 参数
 * keyword（既有语义）/status（ENABLED|DISABLED 精确）/httpMethod（动词精确，null 不命中），
 * 多条件 AND；服务层集成点 = TL 收口签名
 * {@code PermissionUseCase.page(PageQuery, String, EnableStatus, String)}，null=不过滤）。</p>
 *
 * <p>夹具口径（契约无迁移、种子全 ENABLED 且无同名样例 → 用例自备确定性数据行）：
 * {@code @BeforeEach} 经 JdbcTemplate 直插 7 行（id 921465301~921465307，code 前缀
 * {@code it:filter:req214653:*}＋1 行 httpPath 陷阱行 {@code it:trap:hidden}），
 * {@code @AfterEach} 按 id 区间物理清理（共享容器脏数据不外溢，DeptApi 先例口径）。
 * 断言值全部由契约语义＋夹具已知行推导，不读任何实现。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-B1 keyword 大小写语义未在契约钉死（LIKE/ILIKE 未知）：用例关键字与样例同大小写
 *       （{@code req214653}），对两种语义结果一致，用例确定。</li>
 *   <li>AMB-B2 BAD_REQUEST 的 wire 码未在契约给出：AC-7 断言 HTTP 400＋Result 信封 code≠0
 *       （勿断言具体三段式码），400 即同时排除 500。</li>
 *   <li>AMB-B3 HTTP 无参默认 page size 未钉死（AC-1「行为与现状一致」）：断言采用
 *       「total=DB 真值动态计数＋records 顺序=DB permissionCode asc 序列同长前缀」，不钉死条数。</li>
 *   <li>AMB-B4 服务层 PageQuery 上限=框架 {@code PageQuery.MAX_SIZE=200}（round3 修正：size=500
 *       撞上限抛 InvalidRequestException；RolesPage {@code permissionList(1, 500)} 系 HTTP 面范围外
 *       既有缺陷，服务层协议先例不成立，TL 裁决）：全量断言 size=200，夹具 7 行＋种子 68 行=75
 *       &lt; 200，单页即全量。</li>
 *   <li>AMB-B5 夹具直插列清单取自 V2 种子脚本 INSERT 列面（数据面申报；业务实现零读取）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-perm-filter-it-secret-0123456789"
})
class REQ20261120214653PermissionFilterApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 关键字样例：仅命中夹具 6 行的 code（夹具名称不含它、种子码不含它），trap 行仅 httpPath 含它。 */
    private static final String KEYWORD = "req214653";

    /** 名称通道关键字：仅夹具某行名称含该唯一子串（其 code 不含），证明 name 侧匹配。 */
    private static final String KEYWORD_BY_NAME = "样例丙z7k";

    /** 夹具行 id 基点（种子权限 id 1~68；远大于种子与雪花序号可能撞车区间，teardown 按 id 区间清理）。 */
    private static final long ID_BASE = 921_465_300L;

    private static final String CODE_A = "it:filter:req214653:a"; // GET    / ENABLED
    private static final String CODE_B = "it:filter:req214653:b"; // POST   / ENABLED
    private static final String CODE_C = "it:filter:req214653:c"; // GET    / DISABLED
    private static final String CODE_D = "it:filter:req214653:d"; // null   / ENABLED（非接口型）
    private static final String CODE_E = "it:filter:req214653:e"; // null   / DISABLED（非接口型）
    private static final String CODE_F = "it:filter:req214653:f"; // PUT    / ENABLED
    private static final String CODE_TRAP = "it:trap:hidden";     // POST   / httpPath 含关键字但 code/name 不含

    /** 夹具 7 行：{序号, code, name, http_method, http_path, status}（列清单=V2 种子 INSERT 口径，AMB-B5）。 */
    private static final Object[][] FIXTURE_ROWS = {
            {1L, CODE_A, "样例甲z7k", "GET", "/it/filter/a", "ENABLED"},
            {2L, CODE_B, "样例乙z7k", "POST", "/it/filter/b", "ENABLED"},
            {3L, CODE_C, "样例丙z7k", "GET", "/it/filter/c", "DISABLED"},
            {4L, CODE_D, "样例丁z7k", null, null, "ENABLED"},
            {5L, CODE_E, "样例戊z7k", null, null, "DISABLED"},
            {6L, CODE_F, "样例己z7k", "PUT", "/it/filter/f", "ENABLED"},
            {7L, CODE_TRAP, "路径陷阱", "POST", "/it/filter/req214653-hidden", "ENABLED"},
    };

    private static final String INSERT_PERMISSION = """
            insert into sys_permission (id, permission_code, permission_name, permission_type, http_method,
                                        http_path, status, created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, ?, ?, 'API', ?, ?, ?, now(), 'req214653-it', now(), 'req214653-it', 0, false)
            """;

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

    @Autowired
    private PermissionUseCase permissionUseCase;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 真实鉴权过滤器（内部令牌校验）+ 真实 MVC 拦截器链（含权限判定）——DeptApi 先例口径
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        cleanFixtureRows();
        for (Object[] row : FIXTURE_ROWS) {
            jdbcTemplate.update(INSERT_PERMISSION,
                    ID_BASE + (Long) row[0], row[1], row[2], row[3], row[4], row[5]);
        }
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：按 id 区间物理清理夹具行（种子数据不动），并清上下文线程残留
        cleanFixtureRows();
        TenantContext.clear();
        UserContext.clear();
    }

    private void cleanFixtureRows() {
        jdbcTemplate.update("delete from sys_permission where id between ? and ?",
                ID_BASE + 1, ID_BASE + FIXTURE_ROWS.length);
    }

    // ---------- AC-1：不携带任何筛选参数 → 与改造前行为一致（回归兼容，服务面） ----------

    @Test
    void should_return_baseline_sorted_page_when_no_filter_given() {
        var page = permissionUseCase.page(new PageQuery(1, 200), null, null, null);
        assertThat(page.total()).isEqualTo(
                jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class));
        // 排序 permissionCode asc 不变：与同库 ORDER BY 序列逐项全等（同 DB 同排序口径，无 Java/PG 排序歧义）
        assertThat(codes(page.records())).containsExactlyElementsOf(jdbcTemplate.queryForList(
                "select permission_code from sys_permission order by permission_code asc", String.class));
    }

    // ---------- AC-2：keyword 仅匹配 code/name（现状语义，范围不扩展） ----------

    @Test
    void should_match_keyword_on_code_and_name_only_when_keyword_given() {
        // code 通道：6 条夹具 code 均含关键字（名称不含）
        var byCode = permissionUseCase.page(new PageQuery(1, 200), KEYWORD, null, null);
        assertThat(byCode.total()).isEqualTo(6L);
        assertThat(codes(byCode.records())).containsExactlyInAnyOrder(
                CODE_A, CODE_B, CODE_C, CODE_D, CODE_E, CODE_F);
        // 范围不扩展：httpPath 含关键字但 code/name 不含 → 不命中
        assertThat(codes(byCode.records())).doesNotContain(CODE_TRAP);

        // name 通道：唯一名称子串 → 恰 1 条（其 code 不含该子串，证明按 permissionName 匹配）
        var byName = permissionUseCase.page(new PageQuery(1, 200), KEYWORD_BY_NAME, null, null);
        assertThat(codes(byName.records())).containsExactly(CODE_C);
    }

    // ---------- AC-3：status=DISABLED 精确匹配 → 仅停用权限点 ----------

    @Test
    void should_return_only_disabled_rows_when_status_disabled_given() {
        var page = permissionUseCase.page(new PageQuery(1, 200), null, EnableStatus.DISABLED, null);
        assertThat(page.records()).allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(EnableStatus.DISABLED));
        assertThat(codes(page.records())).contains(CODE_C, CODE_E)
                .doesNotContain(CODE_A, CODE_B, CODE_D, CODE_F, CODE_TRAP);
        assertThat(page.total()).isEqualTo(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where status='DISABLED'", Long.class));
    }

    // ---------- AC-4：httpMethod=POST 精确匹配 → 仅 POST 记录 ----------

    @Test
    void should_return_only_post_rows_when_http_method_post_given() {
        var page = permissionUseCase.page(new PageQuery(1, 200), null, null, "POST");
        assertThat(page.records()).allSatisfy(p -> assertThat(p.getHttpMethod()).isEqualTo("POST"));
        assertThat(codes(page.records())).contains(CODE_B, CODE_TRAP)
                .doesNotContain(CODE_A, CODE_C, CODE_D, CODE_E, CODE_F);
        assertThat(page.total()).isEqualTo(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where http_method='POST'", Long.class));
    }

    // ---------- AC-5：http_method 为 null 的权限点不命中任何动词筛选 ----------

    @Test
    void should_exclude_null_http_method_rows_when_http_method_get_given() {
        var page = permissionUseCase.page(new PageQuery(1, 200), null, null, "GET");
        // 每条命中记录 httpMethod 恒为 GET（非 null）——null=非接口型，不匹配任何动词
        assertThat(page.records()).allSatisfy(p -> assertThat(p.getHttpMethod()).isEqualTo("GET"));
        assertThat(codes(page.records())).contains(CODE_A, CODE_C)
                .doesNotContain(CODE_D, CODE_E);
        assertThat(page.total()).isEqualTo(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where http_method='GET'", Long.class));
    }

    // ---------- AC-6：多条件组合 → AND 交集 ----------

    @Test
    void should_intersect_all_conditions_when_combined_filters_given() {
        // keyword ＋ status=DISABLED ＋ httpMethod=GET → 恰为 c（a ENABLED；e 无方法）
        assertThat(codes(permissionUseCase.page(new PageQuery(1, 200), KEYWORD, EnableStatus.DISABLED, "GET").records()))
                .containsExactly(CODE_C);
        // keyword ＋ status=ENABLED ＋ httpMethod=POST → 恰为 b
        assertThat(codes(permissionUseCase.page(new PageQuery(1, 200), KEYWORD, EnableStatus.ENABLED, "POST").records()))
                .containsExactly(CODE_B);
        // keyword ＋ status=DISABLED（不限方法）→ {c, e}
        assertThat(codes(permissionUseCase.page(new PageQuery(1, 200), KEYWORD, EnableStatus.DISABLED, null).records()))
                .containsExactlyInAnyOrder(CODE_C, CODE_E);
    }

    // ---------- AC-8：命中数超过页大小 → total=命中总数、分页正确、排序 permissionCode asc ----------

    @Test
    void should_paginate_and_keep_code_order_when_filtered_hits_exceed_page_size() {
        var p1 = permissionUseCase.page(new PageQuery(1, 2), KEYWORD, null, null);
        var p2 = permissionUseCase.page(new PageQuery(2, 2), KEYWORD, null, null);
        var p3 = permissionUseCase.page(new PageQuery(3, 2), KEYWORD, null, null);
        assertThat(p1.total()).isEqualTo(6L);
        assertThat(p2.total()).isEqualTo(6L);
        assertThat(p3.total()).isEqualTo(6L);
        // 夹具码同前缀仅尾字母差异，asc 序确定：a<b<c<d<e<f；跨页不重不漏且全序一致
        assertThat(codes(p1.records())).containsExactly(CODE_A, CODE_B);
        assertThat(codes(p2.records())).containsExactly(CODE_C, CODE_D);
        assertThat(codes(p3.records())).containsExactly(CODE_E, CODE_F);
    }

    // ---------- AC-9：条件无任何命中 → 空列表、total=0（正常响应，非报错） ----------

    @Test
    void should_return_empty_page_when_no_row_matches_filters() {
        // 合法条件但无交集：DISABLED×PUT → 夹具中 f(PUT) 为 ENABLED、c/e(DISABLED) 非 PUT
        var comboEmpty = permissionUseCase.page(new PageQuery(1, 50), KEYWORD, EnableStatus.DISABLED, "PUT");
        assertThat(comboEmpty.records()).isEmpty();
        assertThat(comboEmpty.total()).isZero();

        var unknownKeyword = permissionUseCase.page(
                new PageQuery(1, 50), "req214653-no-such-substring", null, null);
        assertThat(unknownKeyword.records()).isEmpty();
        assertThat(unknownKeyword.total()).isZero();
    }

    // ---------- AC-1（HTTP 面）：GET 无筛选参数 → 现状回归兼容（Result 信封 + 分页 + 排序） ----------

    @Test
    void should_keep_baseline_behavior_when_list_called_without_filter_params() throws Exception {
        JsonNode body = call(get("/admin/v1/system/permissions"));
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");

        JsonNode data = body.path("data");
        assertThat(data.path("total").asLong()).isEqualTo(
                jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class));
        List<String> wireCodes = new ArrayList<>();
        data.path("records").forEach(n -> wireCodes.add(n.path("permissionCode").asText()));
        assertThat(wireCodes).isNotEmpty();
        // wire 序列 = DB permissionCode asc 序列同长前缀（默认 page size 未钉死，AMB-B3）
        List<String> dbSorted = jdbcTemplate.queryForList(
                "select permission_code from sys_permission order by permission_code asc", String.class);
        assertThat(dbSorted.subList(0, wireCodes.size())).containsExactlyElementsOf(wireCodes);
    }

    // ---------- AC-3/4/6（HTTP 面）：合法筛选参数经 query 串全链路生效 ----------

    @Test
    void should_apply_all_filter_params_via_query_string_when_valid_values_given() throws Exception {
        JsonNode body = call(get("/admin/v1/system/permissions")
                .queryParam("keyword", KEYWORD)
                .queryParam("status", "DISABLED")
                .queryParam("httpMethod", "GET"));
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");

        JsonNode data = body.path("data");
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        JsonNode first = data.path("records").get(0);
        assertThat(first.path("permissionCode").asText()).isEqualTo(CODE_C);
        assertThat(first.path("status").asText()).isEqualTo("DISABLED");
        assertThat(first.path("httpMethod").asText()).isEqualTo("GET");
    }

    // ---------- AC-7：非法参数值 → HTTP 400＋Result 信封 BAD_REQUEST，不 500 ----------

    @Test
    void should_reject_with_400_and_envelope_when_status_value_illegal() throws Exception {
        MvcResult result = performAuthed(get("/admin/v1/system/permissions").queryParam("status", "FOO"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText())
                .as("Result 信封 BAD_REQUEST：code≠0（wire 码未钉死，AMB-B2）").isNotEqualTo("0");
        assertThat(body.path("success").asBoolean()).isFalse();
    }

    @Test
    void should_reject_with_400_and_envelope_when_http_method_verb_illegal() throws Exception {
        MvcResult result = performAuthed(get("/admin/v1/system/permissions").queryParam("httpMethod", "BAR"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText())
                .as("动词白名单拒绝：Result 信封 code≠0（wire 码未钉死，AMB-B2）").isNotEqualTo("0");
        assertThat(body.path("success").asBoolean()).isFalse();
    }

    // ---------- 支撑 ----------

    /** 服务面 page 返回领域实体 {@code PageResult<SysPermission>}（POJO getter 口径，round2 修正；DTO 仅 HTTP 面）。 */
    private static List<String> codes(List<SysPermission> records) {
        return records.stream().map(SysPermission::getPermissionCode).toList();
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(performAuthed(request));
    }

    private MvcResult performAuthed(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token("1", "default", Set.of("super_admin")))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String token(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, Channel.B, roles));
    }
}
