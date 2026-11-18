package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.infra.application.AuditIngestUseCase;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261114-120104 审计查询数据范围回归（platform-infra）服务域集成测试（Testcontainers PG，
 * 装配口径照抄 {@code InfraIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p><b>落位说明（委派单歧义，AMB-0）</b>：委派单指定用例落 platform-system-service/src/test，但 AC-5 的
 * 被测面（既有 audit evaluator 改造为 scope 模型后的审计查询过滤）端点 {@code GET /admin/v1/infra/audit-events}
 * 与 {@code AuditDataPermissionEvaluator} 均在 platform-infra，platform-system 测试类路径不可达；
 * 本用例按「用例跟被测面落位」原则放 platform-infra-service/src/test，REQ 前缀不变，TL 可一键移动。</p>
 *
 * <p>覆盖：AC-5 审计回归——scope=ALL 角色全量、非 ALL（SELF/DEPT/DEPT_AND_CHILD/CUSTOM）按①模型过滤
 * （SELF=本人事件；DEPT=本部门成员事件；DEPT_AND_CHILD=含子部门；CUSTOM=sys_role_dept 自定义部门集）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>改造后 evaluator 的协作面（AuthorizationGateway 布尔 vs scope 查库）实现未交付且禁读实现——
 *       桩件双兼容：功能权限点 {@code infra:audit:list} 一律放行；全量视图开关
 *       {@code infra:audit:list:all} 仅对 ALL 档 requester（userId=41）放行（旧接线口径）；新 scope 查库
 *       接线（sys_role.data_scope/sys_user.dept_id/sys_dept 子树）由本用例 DDL+种子真实驱动——AMB-4。
 *       若交付接线为「scope 经 gateway 决策」，按交付 API 对齐桩件。</li>
 *   <li>scope 查库所需的 sys_* 表在本模块 Flyway 不存在（平台表归 platform-system V1/V9）：
 *       用例以 IF NOT EXISTS 内联建表（列集=V1 基线精确拷贝 + 契约 V9 明示列 data_scope/dept_id/
 *       position_id；sys_dept/sys_role_dept 列名按 menu/绑定表命名惯例假定）——存在实现漂移风险，
 *       交付后以真实 V9 列名对齐——AMB-5。</li>
 *   <li>DEPT/DEPT_AND_CHILD/CUSTOM 档断言「事件操作者→用户→部门」的成员关系过滤（E1=操作者42∈D1、
 *       E2=操作者43∈D2（D2 为 D1 子部门）、E3=操作者44∈D3 旁支）；事件归属用户的解析口径未指明
 *       （operator_id ↔ sys_user 关联假定）——AMB-6。</li>
 *   <li>SELF 档=operator_id 等于当前 userId（旧「本人视图」语义回归）——AMB-6 同源。</li>
 *   <li>旧模型（infra:audit:list:all 全量 / 否则本人）与新模型（scope 档）的分叉点在 evaluator：
 *       SELF 档在两种接线下均应通过；DEPT/DC/CUSTOM 档仅在新 scope 接线下通过——AMB-4 的验收边界。</li>
 *   <li>审计事件以 {@code AuditIngestUseCase} 直灌（Kafka 关闭，InfraIntegrationTest 先例）；
 *       跨租户全量语义（旧权限点名「全租户审计视图」）不在本用例断言，AMB-7。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-auditscope-it-secret-0123456789a"
})
class REQ20261114120104AuditScopeApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String EVENT_1 = "evt-120104-e1";
    private static final String EVENT_2 = "evt-120104-e2";
    private static final String EVENT_3 = "evt-120104-e3";

    /** 部门：501 根、502 为 501 子部门、503 旁支根。 */
    private static final long DEPT_1 = 501L;
    private static final long DEPT_2 = 502L;
    private static final long DEPT_3 = 503L;

    /** 事件操作者（用户 42∈D1、43∈D2、44∈D3）。 */
    private static final long OP_1 = 42L;
    private static final long OP_2 = 43L;
    private static final long OP_3 = 44L;

    /** requester：41+ALL、42+SELF（本人事件=42）、45+DEPT、46+DEPT_AND_CHILD、47+CUSTOM（均挂 D1）。 */
    private static final long REQ_ALL = 41L;
    private static final long REQ_SELF = 42L;
    private static final long REQ_DEPT = 45L;
    private static final long REQ_DC = 46L;
    private static final long REQ_CUSTOM = 47L;

    private static final String ROLE_ALL = "req120104_audit_all";
    private static final String ROLE_DEPT = "req120104_audit_dept";
    private static final String ROLE_DC = "req120104_audit_dc";
    private static final String ROLE_SELF = "req120104_audit_self";
    private static final String ROLE_CUSTOM = "req120104_audit_custom";

    private static final long ROLE_ID_ALL = 7201L;
    private static final long ROLE_ID_DEPT = 7202L;
    private static final long ROLE_ID_DC = 7203L;
    private static final long ROLE_ID_SELF = 7204L;
    private static final long ROLE_ID_CUSTOM = 7205L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** 本地盘存储根（REQ-20261114-071607 起服务装配含 framework-storage；type=local 缺省，root-dir 必配）。 */
    private static final java.nio.file.Path STORAGE_ROOT = createStorageRoot();

    private static java.nio.file.Path createStorageRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("req120104-audit-it-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private AuditIngestUseCase ingestUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 功能权限/全量视图开关经 gateway 决策（既有接线）：桩件口径见类注释 AMB-4。 */
    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    /**
     * 数据范围解析经 platform-system DataScopeClient（交付接线，AMB-4「scope 经 gateway 决策」分支）：
     * 桩件以本用例种子库模拟 /internal/v1/authorization/data-scope 走库口径
     * （角色档最宽 + 部门子树展开 + 部门→用户集），AMB-5 列名/路径形态按交付 V9 对齐。
     */
    @MockitoBean
    private com.acme.platform.infra.infrastructure.client.DataScopeGateway dataScopeGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();

        // 桩件：功能权限点放行；全量视图开关仅 ALL 档 requester 放行（旧接线口径，AMB-4）
        when(authorizationGateway.hasPermission(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            if ("infra:audit:list:all".equals(code)) {
                return String.valueOf(REQ_ALL).equals(currentUserId());
            }
            return true;
        });

        // 数据范围桩件（交付接线对齐，见字段注）：以种子库模拟 platform-system 数据范围解析走库口径
        when(dataScopeGateway.currentDataScope()).thenAnswer(invocation -> emulateSystemDataScope());

        createScopeTablesIfAbsent();
        cleanScopeData();
        seedScopeModel();
        seedAuditEvents();
    }

    @AfterEach
    void cleanUp() {
        cleanScopeData();
        jdbcTemplate.update("delete from acme_op_audit");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-5：scope=ALL → 审计全量 ----------

    @Test
    void should_seeAllEvents_when_roleScopeAll() throws Exception {
        JsonNode body = listAuditEvents(String.valueOf(REQ_ALL), Set.of(ROLE_ALL));

        assertThat(eventIds(body)).containsExactlyInAnyOrder(EVENT_1, EVENT_2, EVENT_3);
    }

    // ---------- AC-5：scope=SELF → 仅本人事件（旧「本人视图」语义回归） ----------

    @Test
    void should_seeOnlyOwnEvents_when_roleScopeSelf() throws Exception {
        JsonNode body = listAuditEvents(String.valueOf(REQ_SELF), Set.of(ROLE_SELF));

        assertThat(eventIds(body)).as("SELF 档应仅见本人（操作者42）事件").containsExactly(EVENT_1);
    }

    // ---------- AC-5：scope=DEPT → 仅本部门成员事件 ----------

    @Test
    void should_seeDirectDeptMemberEvents_when_roleScopeDept() throws Exception {
        JsonNode body = listAuditEvents(String.valueOf(REQ_DEPT), Set.of(ROLE_DEPT));

        assertThat(eventIds(body)).as("DEPT 档应仅见 D1 成员（操作者42）事件").containsExactly(EVENT_1);
    }

    // ---------- AC-5：scope=DEPT_AND_CHILD → 含子部门成员事件 ----------

    @Test
    void should_seeDeptAndChildMemberEvents_when_roleScopeDeptAndChild() throws Exception {
        JsonNode body = listAuditEvents(String.valueOf(REQ_DC), Set.of(ROLE_DC));

        assertThat(eventIds(body))
                .as("DEPT_AND_CHILD 档应见 D1+子部门 D2 成员事件、不含旁支 D3")
                .containsExactlyInAnyOrder(EVENT_1, EVENT_2);
    }

    // ---------- AC-5：scope=CUSTOM → sys_role_dept 自定义部门集成员事件 ----------

    @Test
    void should_seeCustomDeptMemberEvents_when_roleScopeCustom() throws Exception {
        JsonNode body = listAuditEvents(String.valueOf(REQ_CUSTOM), Set.of(ROLE_CUSTOM));

        assertThat(eventIds(body)).as("CUSTOM 档（deptIds=[D1]）应仅见 D1 成员事件").containsExactly(EVENT_1);
    }

    // ---------- 支撑 ----------

    /**
     * 桩件模拟 platform-system 数据范围解析（/internal/v1/authorization/data-scope 走库口径）：
     * 主体角色档取最宽（null 归 ALL），DEPT/DEPT_AND_CHILD/CUSTOM 展开可见部门集并解析部门内用户集，
     * SELF 档=本人——与 AuditDataPermissionEvaluator 消费的 DataScopeView 契约一致。
     */
    private com.acme.platform.system.api.DataScopeView emulateSystemDataScope() {
        String userId = currentUserId();
        if (userId == null || userId.isBlank()) {
            return new com.acme.platform.system.api.DataScopeView("SELF", List.of(), List.of());
        }
        List<Long> roleIds = jdbcTemplate.queryForList(
                "select role_id from sys_user_role where user_id = ?", Long.class, Long.parseLong(userId));
        String scope = "SELF";
        if (!roleIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
            List<String> scopes = jdbcTemplate.queryForList(
                    "select coalesce(data_scope, 'ALL') from sys_role where id in (" + placeholders + ")",
                    String.class, roleIds.toArray());
            scope = scopes.stream()
                    .max(java.util.Comparator.comparingInt(REQ20261114120104AuditScopeApiIntegrationTest::scopeRank))
                    .orElse("SELF");
        }
        return switch (scope) {
            case "ALL" -> new com.acme.platform.system.api.DataScopeView("ALL", List.of(), List.of());
            case "SELF" -> new com.acme.platform.system.api.DataScopeView("SELF", List.of(), List.of(userId));
            default -> scopedView(scope, userId, roleIds);
        };
    }

    /** 部门档可见集展开（DEPT=本部门、DEPT_AND_CHILD=path 前缀子树、CUSTOM=sys_role_dept 集合）。 */
    private com.acme.platform.system.api.DataScopeView scopedView(String scope, String userId, List<Long> roleIds) {
        Long deptId = jdbcTemplate.queryForObject(
                "select dept_id from sys_user where id = ?", Long.class, Long.parseLong(userId));
        List<Long> deptIds = switch (scope) {
            case "DEPT" -> deptId == null ? List.of() : List.of(deptId);
            case "DEPT_AND_CHILD" -> deptId == null ? List.of() : jdbcTemplate.queryForList(
                    "select id from sys_dept where path like ?", Long.class, "/" + deptId + "/%");
            case "CUSTOM" -> roleIds.isEmpty() ? List.of() : jdbcTemplate.queryForList(
                    "select dept_id from sys_role_dept where role_id in ("
                            + String.join(",", java.util.Collections.nCopies(roleIds.size(), "?")) + ")",
                    Long.class, roleIds.toArray());
            default -> List.of();
        };
        if (deptIds.isEmpty()) {
            return new com.acme.platform.system.api.DataScopeView(scope, List.of(), List.of());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(deptIds.size(), "?"));
        List<String> userIds = jdbcTemplate.queryForList(
                "select cast(id as varchar) from sys_user where dept_id in (" + placeholders + ")",
                String.class, deptIds.toArray());
        return new com.acme.platform.system.api.DataScopeView(scope, deptIds, userIds);
    }

    /** 档位宽序（ALL &gt; DEPT_AND_CHILD &gt; DEPT &gt; CUSTOM &gt; SELF，未知取值视为最窄）。 */
    private static int scopeRank(String scope) {
        return switch (scope) {
            case "ALL" -> 5;
            case "DEPT_AND_CHILD" -> 4;
            case "DEPT" -> 3;
            case "CUSTOM" -> 2;
            case "SELF" -> 1;
            default -> 0;
        };
    }

    private JsonNode listAuditEvents(String userId, Set<String> roles) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/v1/infra/audit-events")
                .param("page", "1").param("size", "50");
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token(userId, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).as("审计事件查询应成功").isEqualTo("0");
        return body;
    }

    private String token(String userId, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, "default", Channel.B, roles));
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 请求线程当前 userId（UserContext 由 InternalTokenAuthFilter 从 token claims 绑定）。 */
    private static String currentUserId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }

    private void seedAuditEvents() {
        ingestUseCase.ingest(event(EVENT_1, OP_1));
        ingestUseCase.ingest(event(EVENT_2, OP_2));
        ingestUseCase.ingest(event(EVENT_3, OP_3));
    }

    /** 12 参构造与 InfraIntegrationTest 同形（eventId, traceId, uri, tenantId, operatorId, channel, action, module, targetId, detail, occurredAt, operator）。 */
    private static AuditEvent event(String eventId, long operatorId) {
        return new AuditEvent(eventId, eventId + "-trace", null, "default", String.valueOf(operatorId), "B",
                "order.create", "order", "1", null, Instant.now(), "req120104-it");
    }

    private void seedScopeModel() {
        // 部门树（path 物化路径为交付 V9 真实形态 "/父链/id/" 尾斜杠，AMB-5 对齐）
        insertDept(DEPT_1, 0L, "req-120104-audit-d1", "/" + DEPT_1 + "/");
        insertDept(DEPT_2, DEPT_1, "req-120104-audit-d2", "/" + DEPT_1 + "/" + DEPT_2 + "/");
        insertDept(DEPT_3, 0L, "req-120104-audit-d3", "/" + DEPT_3 + "/");
        // 事件操作者用户 + requester 用户（sys_user.dept_id 为契约 V9 明示列）；
        // SELF requester 即操作者 42（本人事件=42），不复插入避免主键冲突（测试装配层修正）
        insertUser(OP_1, "req120104-audit-op1", DEPT_1);
        insertUser(OP_2, "req120104-audit-op2", DEPT_2);
        insertUser(OP_3, "req120104-audit-op3", DEPT_3);
        insertUser(REQ_ALL, "req120104-audit-req-all", DEPT_1);
        insertUser(REQ_DEPT, "req120104-audit-req-dept", DEPT_1);
        insertUser(REQ_DC, "req120104-audit-req-dc", DEPT_1);
        insertUser(REQ_CUSTOM, "req120104-audit-req-custom", DEPT_1);
        // 五档角色（sys_role.data_scope 为契约 V9 明示列）
        insertRole(ROLE_ID_ALL, ROLE_ALL, "ALL");
        insertRole(ROLE_ID_DEPT, ROLE_DEPT, "DEPT");
        insertRole(ROLE_ID_DC, ROLE_DC, "DEPT_AND_CHILD");
        insertRole(ROLE_ID_SELF, ROLE_SELF, "SELF");
        insertRole(ROLE_ID_CUSTOM, ROLE_CUSTOM, "CUSTOM");
        // 用户-角色绑定（DB 解析路径；令牌 role claims 为 claims 解析路径，AMB-2 同款双路径）
        insertUserRole(REQ_ALL, ROLE_ID_ALL);
        insertUserRole(REQ_SELF, ROLE_ID_SELF);
        insertUserRole(REQ_DEPT, ROLE_ID_DEPT);
        insertUserRole(REQ_DC, ROLE_ID_DC);
        insertUserRole(REQ_CUSTOM, ROLE_ID_CUSTOM);
        // CUSTOM 自定义部门集（sys_role_dept 为契约 V9 明示关联）
        insertRoleDept(ROLE_ID_CUSTOM, DEPT_1);
    }

    private void createScopeTablesIfAbsent() {
        // 列集=V1__init_schema.sql 基线拷贝 + 契约 V9 明示列（AMB-5：实现交付后以真实列名对齐）
        jdbcTemplate.execute("""
                create table if not exists sys_dept (
                    id          bigint primary key,
                    tenant_id   varchar(64) not null,
                    parent_id   bigint      not null,
                    path        varchar(255),
                    dept_name   varchar(64) not null,
                    sort_order  integer     not null default 0,
                    status      varchar(16) not null default 'ENABLED',
                    created_at  timestamptz not null default now(),
                    created_by  varchar(64) not null default 'req120104-it',
                    updated_at  timestamptz not null default now(),
                    updated_by  varchar(64) not null default 'req120104-it',
                    version     bigint      not null default 0,
                    deleted     boolean     not null default false
                )""");
        jdbcTemplate.execute("""
                create table if not exists sys_user (
                    id          bigint primary key,
                    tenant_id   varchar(64) not null,
                    username    varchar(64) not null,
                    nickname    varchar(64) not null,
                    email       varchar(128),
                    phone       varchar(32),
                    status      varchar(16) not null,
                    built_in    boolean     not null,
                    dept_id     bigint,
                    position_id bigint,
                    created_at  timestamptz not null,
                    created_by  varchar(64) not null,
                    updated_at  timestamptz not null,
                    updated_by  varchar(64) not null,
                    version     bigint      not null,
                    deleted     boolean     not null,
                    constraint uk_sys_user_username unique (username)
                )""");
        jdbcTemplate.execute("""
                create table if not exists sys_role (
                    id         bigint primary key,
                    tenant_id  varchar(64) not null,
                    role_code  varchar(64) not null,
                    role_name  varchar(64) not null,
                    status     varchar(16) not null,
                    remark     varchar(255),
                    built_in   boolean     not null,
                    data_scope varchar(16),
                    created_at timestamptz not null,
                    created_by varchar(64) not null,
                    updated_at timestamptz not null,
                    updated_by varchar(64) not null,
                    version    bigint      not null,
                    deleted    boolean     not null,
                    constraint uk_sys_role_tenant_code unique (tenant_id, role_code)
                )""");
        jdbcTemplate.execute("""
                create table if not exists sys_user_role (
                    id         bigint primary key,
                    tenant_id  varchar(64) not null,
                    user_id    bigint      not null,
                    role_id    bigint      not null,
                    created_at timestamptz not null,
                    created_by varchar(64) not null,
                    updated_at timestamptz not null,
                    updated_by varchar(64) not null,
                    version    bigint      not null,
                    deleted    boolean     not null,
                    constraint uk_sys_user_role unique (tenant_id, user_id, role_id)
                )""");
        jdbcTemplate.execute("""
                create table if not exists sys_role_dept (
                    id         bigint primary key,
                    tenant_id  varchar(64) not null,
                    role_id    bigint      not null,
                    dept_id    bigint      not null,
                    created_at timestamptz not null,
                    created_by varchar(64) not null,
                    updated_at timestamptz not null,
                    updated_by varchar(64) not null,
                    version    bigint      not null,
                    deleted    boolean     not null,
                    constraint uk_sys_role_dept unique (tenant_id, role_id, dept_id)
                )""");
        jdbcTemplate.execute("""
                create table if not exists sys_permission (
                    id              bigint primary key,
                    permission_code varchar(128) not null,
                    permission_name varchar(64)  not null,
                    permission_type varchar(16)  not null,
                    http_method     varchar(16),
                    http_path       varchar(255),
                    status          varchar(16)  not null,
                    created_at      timestamptz  not null,
                    created_by      varchar(64)  not null,
                    updated_at      timestamptz  not null,
                    updated_by      varchar(64)  not null,
                    version         bigint       not null,
                    deleted         boolean      not null,
                    constraint uk_sys_permission_code unique (permission_code)
                )""");
        // 审计端点接口级权限点（V3/V4 种子拷贝，权限拦截面解析用；已存在则跳过）
        jdbcTemplate.execute("""
                insert into sys_permission (id, permission_code, permission_name, permission_type, http_method,
                                            http_path, status, created_at, created_by, updated_at, updated_by,
                                            version, deleted)
                values (32, 'infra:audit:list', '审计事件查询', 'API', 'GET', '/admin/v1/infra/audit-events',
                        'ENABLED', now(), 'req120104-it', now(), 'req120104-it', 0, false)
                on conflict (id) do nothing""");
        jdbcTemplate.execute("""
                insert into sys_permission (id, permission_code, permission_name, permission_type, http_method,
                                            http_path, status, created_at, created_by, updated_at, updated_by,
                                            version, deleted)
                values (33, 'infra:audit:list:all', '全租户审计视图', 'API', 'GET', '/admin/v1/infra/audit-events',
                        'ENABLED', now(), 'req120104-it', now(), 'req120104-it', 0, false)
                on conflict (id) do nothing""");
    }

    private void cleanScopeData() {
        jdbcTemplate.update("delete from sys_role_dept where role_id between 7201 and 7299");
        jdbcTemplate.update("delete from sys_user_role where user_id between 41 and 49");
        jdbcTemplate.update("delete from sys_user where id between 41 and 49");
        jdbcTemplate.update("delete from sys_role where id between 7201 and 7299");
        jdbcTemplate.update("delete from sys_dept where id between 501 and 599");
    }

    private void insertDept(long id, long parentId, String name, String path) {
        jdbcTemplate.update("""
                        insert into sys_dept (id, tenant_id, parent_id, path, dept_name, sort_order, status,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, ?, 1, 'ENABLED', now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                id, parentId, path, name);
    }

    private void insertUser(long id, String username, Long deptId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false, ?, null,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                id, username, username, deptId);
    }

    private void insertRole(long id, String roleCode, String dataScope) {
        jdbcTemplate.update("""
                        insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                              data_scope, created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, 'ENABLED', null, false, ?,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                id, roleCode, roleCode, dataScope);
    }

    private void insertUserRole(long userId, long roleId) {
        jdbcTemplate.update("""
                        insert into sys_user_role (id, tenant_id, user_id, role_id, created_at, created_by,
                                                   updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                userId * 100 + roleId, userId, roleId);
    }

    private void insertRoleDept(long roleId, long deptId) {
        jdbcTemplate.update("""
                        insert into sys_role_dept (id, tenant_id, role_id, dept_id, created_at, created_by,
                                                   updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                roleId * 100 + deptId, roleId, deptId);
    }

    /** 审计记录定位：eventId 字段兼容 id 兜底。 */
    private static List<String> eventIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode record : body.path("data").path("records")) {
            JsonNode field = record.hasNonNull("eventId") ? record.get("eventId") : record.get("id");
            if (field != null) {
                ids.add(field.asText());
            }
        }
        return ids;
    }
}
