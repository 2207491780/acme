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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120102 通知公告（platform-system）服务域集成测试（Testcontainers PG + Redis，照抄
 * {@code SystemIntegrationTest}/{@code REQ20261114071607FileServiceIntegrationTest} 装配口径；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 公告增删改查/发布/下线/删除守卫/不存在公告/无权限统一拒绝、AC-2 发布扇出（每人一条对应
 * message + 重复发布幂等）、V8 权限点种子冒烟（notice/message 八码存在性，支撑权限判定）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}（不断言 4xx，HTTP 200 语义）；
 *       {@code NOTICE_PUBLISHED} 为契约明示错误码，精确断言。</li>
 *   <li>「下线」端点（TL 裁决采纳并正式入契约，原 AMB-1 收敛）：{@code PUT /admin/v1/system/notices/{id}/offline}
 *       （权限 system:notice:publish；OFFLINE 后可编辑/删除/再发布，再发布重新扇出）。</li>
 *   <li>PUT 编辑是否携带乐观锁 version 未指明（工程惯例携带）：详情响应含 version 则回传，否则省略——AMB-2。</li>
 *   <li>公告状态枚举字面量未指明：以行为断言替代（已发布删除被拒 / 下线后可删 / 发布后 publish_at 非空）——AMB-3。</li>
 *   <li>发布扇出同步性未指明：轮询兼容同步/异步落库；重复发布幂等以 2s 观察窗内计数不增验证——AMB-4。</li>
 *   <li>扇出消息字段映射未指明：以 title=公告 title 识别「对应 message」，content 不钉死——AMB-5；
 *       全员口径（ENABLED 过滤 / 租户范围 / 是否含操作者）未指明：仅对预置用户断言每人一条 + 全表无重复扇出，
 *       不钉死扇出名单——AMB-6。</li>
 *   <li>权限判定为本服务 DB 决策（PermissionDecisionService）：正向用例以 super_admin 角色令牌驱动
 *       （依赖 V8 种子对 super_admin 全量绑定新权限点的既有惯例）；无权限用例以未绑定角色令牌驱动，
 *       仅断言 Result.code≠0（真实拒绝码由安全层定义，同范本口径）。</li>
 *   <li>收件人/扇出对象均为预置真实 sys_user（契约未指明 recipient 存在性校验，预置规避两种实现口径分歧）——AMB-8。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-notice-it-secret-0123456789abcdef"
})
class REQ20261114120102NoticeApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 预置收件用户（tenant=default，与令牌租户一致；固定 id 避免与 V2 种子冲突）。 */
    private static final long USER_A = 902001L;
    private static final long USER_B = 902002L;

    private static final Duration FANOUT_SETTLE = Duration.ofSeconds(5);
    private static final Duration IDEMPOTENT_OBSERVE = Duration.ofSeconds(2);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：发布扇出会经 Redis pub/sub 推送在线用户事件（本类无在线会话，仅需总线可用）。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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

    @BeforeEach
    void setUp() {
        // 真实鉴权过滤器（内部令牌校验）+ 真实 MVC 拦截器链（含权限判定）；权限走本服务 DB 决策，无需桩件
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        seedUser(USER_A, "req120102-user-a");
        seedUser(USER_B, "req120102-user-b");
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：物理清表（绕开租户/软删过滤面）+ 清预置用户
        jdbcTemplate.update("delete from sys_notice");
        jdbcTemplate.update("delete from sys_message");
        jdbcTemplate.update("delete from sys_user where id in (?, ?)", USER_A, USER_B);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- V8 权限点种子冒烟（AC-1 权限判定的数据前置） ----------

    @Test
    void flywaySeedsNoticeAndMessagePermissionCodes() {
        // 契约变更清单：权限点种子 notice:list/create/update/delete/publish、message:send/list/read
        // （条数实施时按控制器定——用例按 contains 语义断言契约点名八码，不钉死总数）
        List<String> codes = jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code like 'system:notice:%' "
                        + "or permission_code like 'system:message:%'", String.class);
        assertThat(codes).contains(
                "system:notice:list", "system:notice:create", "system:notice:update",
                "system:notice:delete", "system:notice:publish",
                "system:message:send", "system:message:list", "system:message:read");
    }

    // ---------- AC-1：增/查（详情+分页）/改 生效，富文本 content 原样回显 ----------

    @Test
    void should_createDraftAndGetUpdateAndList_when_noticeCrudWithPermission() throws Exception {
        String content = "<p>hello <b>world</b></p>";
        String id = createNotice("req-120102-crud-title", content);

        JsonNode detail = bodyOf(call(get("/admin/v1/system/notices/{id}", id)));
        assertThat(detail.path("code").asText()).isEqualTo("0");
        JsonNode data = detail.path("data");
        assertThat(textOf(data, "title")).isEqualTo("req-120102-crud-title");
        assertThat(textOf(data, "content")).isEqualTo(content);

        // 编辑（version 惯例：详情含则回传，AMB-2）
        JsonNode updated = bodyOf(call(put("/admin/v1/system/notices/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody("req-120102-crud-title-2",
                        "<p>edited</p>", data)))));
        assertThat(updated.path("code").asText()).isEqualTo("0");

        JsonNode after = bodyOf(call(get("/admin/v1/system/notices/{id}", id)));
        assertThat(textOf(after.path("data"), "title")).isEqualTo("req-120102-crud-title-2");
        assertThat(textOf(after.path("data"), "content")).isEqualTo("<p>edited</p>");

        // 分页列表可见
        JsonNode page = bodyOf(call(get("/admin/v1/system/notices")
                .param("page", "1").param("size", "50")));
        assertThat(page.path("code").asText()).isEqualTo("0");
        assertThat(containsRecord(page.path("data").path("records"), "id", id))
                .as("新建公告应出现在分页列表").isTrue();
    }

    // ---------- AC-2：发布扇出每人一条对应 message；重复发布幂等不重复扇出 ----------

    @Test
    void should_fanOutExactlyOneMessagePerUserAndStayIdempotent_when_publishNoticeTwice() throws Exception {
        String title = "req-120102-fanout-title";
        String id = createNotice(title, "<p>fanout</p>");

        // 草稿不扇出
        assertThat(messageCountFor(USER_A, title)).isZero();
        assertThat(messageCountFor(USER_B, title)).isZero();

        JsonNode published = bodyOf(call(put("/admin/v1/system/notices/{id}/publish", id)));
        assertThat(published.path("code").asText()).isEqualTo("0");

        // 每个用户恰好一条对应 message（轮询兼容同步/异步落库，AMB-4）；publish_at 落库（AMB-3 行为口径）
        assertThat(awaitTrue(FANOUT_SETTLE, () -> fanOutComplete(title, id)))
                .as("发布后应扇出生成每人一条 message 且 publish_at 非空").isTrue();
        assertThat(messageCountFor(USER_A, title)).isEqualTo(1);
        assertThat(messageCountFor(USER_B, title)).isEqualTo(1);
        assertThat(duplicatedRecipientCount(title)).as("任一用户不得出现重复扇出").isZero();

        // 重复发布幂等：仍成功，且观察窗内扇出量不增（AMB-4）
        JsonNode republished = bodyOf(call(put("/admin/v1/system/notices/{id}/publish", id)));
        assertThat(republished.path("code").asText()).isEqualTo("0");
        assertThatStaysTrue(IDEMPOTENT_OBSERVE,
                () -> messageCountFor(USER_A, title) == 1
                        && messageCountFor(USER_B, title) == 1
                        && duplicatedRecipientCount(title) == 0);
    }

    // ---------- AC-1 边界：已发布公告直接删除 → NOTICE_PUBLISHED（先下线） ----------

    @Test
    void should_rejectDeleteWithNoticePublished_when_deletePublishedNotice() throws Exception {
        String title = "req-120102-guard-title";
        String id = createNotice(title, "<p>guard</p>");
        assertThat(bodyOf(call(put("/admin/v1/system/notices/{id}/publish", id)))
                .path("code").asText()).isEqualTo("0");
        assertThat(awaitTrue(FANOUT_SETTLE, () -> fanOutComplete(title, id))).isTrue();

        JsonNode deleted = bodyOf(call(delete("/admin/v1/system/notices/{id}", id)));

        assertThat(deleted.path("success").asBoolean()).isFalse();
        // wire 错误码=三段式布局码（SystemErrorCodes.NOTICE_PUBLISHED，Backend.md 10.5.2）
        assertThat(deleted.path("code").asText()).isEqualTo("platform-system-08-0001");
        // 记录未被软删
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_notice where id=? and deleted=false",
                Long.class, Long.parseLong(id))).isEqualTo(1L);
    }

    // ---------- AC-1 边界：不存在公告 publish/update → NOTICE_NOT_FOUND（TL 追认第 4 枚错误码） ----------

    @Test
    void should_returnNoticeNotFound_when_publishOrUpdateMissingNotice() throws Exception {
        JsonNode published = bodyOf(call(put("/admin/v1/system/notices/{id}/publish", "99999999")));
        assertThat(published.path("success").asBoolean()).isFalse();
        // wire 错误码=三段式布局码（SystemErrorCodes.NOTICE_NOT_FOUND，Backend.md 10.5.2）
        assertThat(published.path("code").asText()).isEqualTo("platform-system-08-0002");

        JsonNode updated = bodyOf(call(put("/admin/v1/system/notices/{id}", "99999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("title", "x", "content", "y")))));
        assertThat(updated.path("success").asBoolean()).isFalse();
        assertThat(updated.path("code").asText()).isEqualTo("platform-system-08-0002");
    }

    // ---------- AC-1：下线后删除（TL 裁决入契约：offline 后可编辑/删除/再发布，再发布重新扇出） ----------

    @Test
    void should_allowDeleteAfterOffline_when_publishedNoticeTakenOfflineThenDeleted() throws Exception {
        String id = createNotice("req-120102-offline-title", "<p>offline</p>");
        assertThat(bodyOf(call(put("/admin/v1/system/notices/{id}/publish", id)))
                .path("code").asText()).isEqualTo("0");

        JsonNode offline = bodyOf(call(put("/admin/v1/system/notices/{id}/offline", id)));
        assertThat(offline.path("code").asText()).as("下线应成功（AMB-1 假定路由 offline）").isEqualTo("0");

        JsonNode deleted = bodyOf(call(delete("/admin/v1/system/notices/{id}", id)));
        assertThat(deleted.path("code").asText()).as("下线后已发布公告可软删").isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from sys_notice where id=?", Boolean.class, Long.parseLong(id))).isTrue();
    }

    // ---------- AC-1：未发布草稿直接软删 ----------

    @Test
    void should_softDeleteDraft_when_deleteUnpublishedNotice() throws Exception {
        String id = createNotice("req-120102-draft-del-title", "<p>draft</p>");

        JsonNode deleted = bodyOf(call(delete("/admin/v1/system/notices/{id}", id)));

        assertThat(deleted.path("code").asText()).isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from sys_notice where id=?", Boolean.class, Long.parseLong(id))).isTrue();
    }

    // ---------- AC-1：无权限 → 统一拒绝（接口级权限面） ----------

    @Test
    void should_rejectAllNoticeEndpoints_when_noPermission() throws Exception {
        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                post("/admin/v1/system/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "x", "content", "y"))),
                get("/admin/v1/system/notices"),
                get("/admin/v1/system/notices/{id}", "1"),
                put("/admin/v1/system/notices/{id}", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "x", "content", "y"))),
                put("/admin/v1/system/notices/{id}/publish", "1"),
                delete("/admin/v1/system/notices/{id}", "1"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = bodyOf(callWithNoPermission(endpoint));
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint.toString()).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint.toString())
                    .isNotEqualTo("0");
        }
    }

    // ---------- 支撑 ----------

    private String createNotice(String title, String content) {
        try {
            JsonNode body = bodyOf(call(post("/admin/v1/system/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("title", title, "content", content)))));
            assertThat(body.path("code").asText()).as("新建公告应成功").isEqualTo("0");
            return body.path("data").path("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("新建公告请求失败", ex);
        }
    }

    /** PUT 编辑体：详情响应含 version（乐观锁惯例）则回传，否则省略（AMB-2）。 */
    private Map<String, Object> updateBody(String title, String content, JsonNode detailData) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        if (detailData.hasNonNull("version")) {
            body.put("version", detailData.get("version"));
        }
        return body;
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, Set.of("super_admin"));
    }

    private MvcResult callWithNoPermission(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, Set.of("req120102-unbound-role"));
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request, Set<String> roles) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token("1", roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String token(String userId, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, "default", Channel.B, roles));
    }

    private void seedUser(long id, String username) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false,
                                now(), 'req120102-it', now(), 'req120102-it', 0, false)
                        """,
                id, username, username);
    }

    /** 扇出完成判定：两个预置用户各一条对应 message + publish_at 落库。 */
    private boolean fanOutComplete(String title, String noticeId) {
        Timestamp publishAt = jdbcTemplate.queryForObject(
                "select publish_at from sys_notice where id=?", Timestamp.class, Long.parseLong(noticeId));
        return messageCountFor(USER_A, title) == 1
                && messageCountFor(USER_B, title) == 1
                && publishAt != null;
    }

    private long messageCountFor(long recipientId, String title) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_message where recipient_id=? and title=?",
                Long.class, recipientId, title);
        return count == null ? 0L : count;
    }

    /** 同一公告扇出中重复出现的用户数（幂等性的全表面口径，不钉死扇出名单，AMB-6）。 */
    private long duplicatedRecipientCount(String title) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from (select recipient_id from sys_message where title=? "
                        + "group by recipient_id having count(*) > 1) d", Long.class, title);
        return count == null ? 0L : count;
    }

    private static boolean containsRecord(JsonNode records, String field, String value) {
        for (JsonNode record : records) {
            if (value.equals(record.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    /** 单字段取值（title/content/id 等驼峰=下划线同名字段，无命名策略分歧）。 */
    private static String textOf(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static boolean awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException pollFailure) {
                // 前置状态未就绪（如迁移尚未落库），继续轮询
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 观察窗内条件恒成立（幂等断言：轮询期任何一次违反即失败）。 */
    private static void assertThatStaysTrue(Duration window, BooleanSupplier condition) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(condition.getAsBoolean()).as("观察窗内幂等条件被违反").isTrue();
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean()).as("观察窗结束幂等条件被违反").isTrue();
    }
}
