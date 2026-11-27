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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261122-150756（JOB-4）内部告警端点（platform-system）集成测试（Testcontainers PG，
 * 装配口径照抄 {@code REQ20261117011514InternalSystemConfigApiTest} internal 先例；
 * 扇出断言照 {@code REQ20261114120102NoticeApiIntegrationTest} 预置用户/轮询口径；
 * 无 Docker 自动跳过——判定口径 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-4（端点腿：POST /internal/v1/notify/messages → NoticeUseCase.publish
 * 全员扇出 → sys_message 落库（+WS/Expo 推送管道））与 AC-8（internal 面 X-Acme-Token 校验同形）。
 * TL 裁决①（2026-11-22）：契约④数据面=sys_message+WS+Expo 管道，sys_notice 公告面不在本期——
 * 断言面只钉 sys_message。scheduler 侧三类触发点+5 分钟去重见
 * {@code REQ20261122150756SchedulerResilienceIntegrationTest}（platform-scheduler 测试域）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 调用方身份=内部令牌 userId=platform-scheduler、租户 claim 为 null（JOB-2 范本 internalCall
 *     同口径）；若实现要求租户 claim 非空，红则 TL 裁决；
 * A-2 无令牌/非法令牌拒绝码断言 401/403 双容忍（AC-8 契约原文「401/403 internal 面校验同形」；
 *     InternalTokenAuthFilter 公开路径为空清单，拒绝走安全层非业务信封）；
 * A-3 V31 说明：契约⑧「仅新增内部端点无 DDL 则迁移文件省略」——本类不断言迁移版本号，
 *     只断言 V8 既有 sys_message 数据面行为（sys_notice 公告面不在本期，TL 裁决①）。</p>
 */
class REQ20261122150756InternalNotifyApiIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String NOTIFY_URL = "/internal/v1/notify/messages";

    private static final String SCHEDULER_SENDER = "platform-scheduler";

    /** 预置收件用户（tenant=default；固定高位 id 避免与种子冲突，范本同款）。 */
    private static final long USER_A = 9_150_756_01L;
    private static final long USER_B = 9_150_756_02L;

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
        jdbcTemplate.update("delete from sys_notice");
        jdbcTemplate.update("delete from sys_message");
        seedUser(USER_A, "req150756-user-a");
        seedUser(USER_B, "req150756-user-b");
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：物理清表（绕开租户/软删过滤面）+ 清预置用户（范本同款）
        jdbcTemplate.update("delete from sys_notice");
        jdbcTemplate.update("delete from sys_message");
        jdbcTemplate.update("delete from sys_user where id in (?, ?)", USER_A, USER_B);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-4（端点腿）：valid token → publish 全员扇出 sys_message（TL 裁决①：sys_notice 不在本期） ----------

    @Test
    void should_fanout_messages_when_internal_notify_called_with_valid_token()
            throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("sender", SCHEDULER_SENDER);
        body.put("title", "req150756-调度告警-重试耗尽");
        body.put("content", "job it150756-dedup execution 91507560001 FAILED NO_EXECUTOR");

        JsonNode resp = internalNotify(body);
        assertThat(resp.path("success").asBoolean())
                .as("内部告警应受理成功：%s", resp).isTrue();
        assertThat(resp.path("code").asText()).as("受理 code 应为 0").isEqualTo("0");

        // 扇出落库（异步发布轮询窗口，范本 fanOutComplete 口径；TL 裁决①：数据面=sys_message）
        boolean fannedOut = await(8_000, () ->
                messageCountFor(USER_A, "req150756-调度告警-重试耗尽") >= 1
                        && messageCountFor(USER_B, "req150756-调度告警-重试耗尽") >= 1);
        assertThat(fannedOut)
                .as("publish 应扇出：预置用户各 ≥1 条 sys_message").isTrue();

        // 站内信收件语义：未读=false 初始
        Long unread = jdbcTemplate.queryForObject(
                "select count(*) from sys_message where recipient_id = ? and title = ? and is_read = false",
                Long.class, USER_A, "req150756-调度告警-重试耗尽");
        assertThat(unread).as("新站内信应为未读初始态").isGreaterThanOrEqualTo(1L);
    }

    // ---------- AC-4（端点腿）：content 可缺省（契约④ body={sender,title,content?}） ----------

    @Test
    void should_fanout_message_without_content_when_content_omitted() throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("sender", SCHEDULER_SENDER);
        body.put("title", "req150756-调度告警-无正文");

        JsonNode resp = internalNotify(body);
        assertThat(resp.path("success").asBoolean())
                .as("content 缺省应受理成功：%s", resp).isTrue();
        assertThat(resp.path("code").asText()).as("受理 code 应为 0").isEqualTo("0");

        boolean messageInPlace = await(8_000, () ->
                messageCountFor(USER_A, "req150756-调度告警-无正文") >= 1);
        assertThat(messageInPlace).as("content 缺省仍应扇出 sys_message（TL 裁决①）").isTrue();
    }

    // ---------- AC-8：无令牌 → 401/403 internal 面校验同形，且不产生数据 ----------

    @Test
    void should_reject_without_token_when_internal_notify_called() throws Exception {
        long messageCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_message", Long.class);

        ObjectNode body = JSON.createObjectNode();
        body.put("sender", SCHEDULER_SENDER);
        body.put("title", "req150756-无令牌-不得落库");

        MvcResult result = mvc.perform(post(NOTIFY_URL)
                        .header("X-Acme-Channel", "B")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status).as("无令牌应被 internal 面拒绝（AC-8 401/403 同形，A-2）").isIn(401, 403);

        long messageCountAfter = jdbcTemplate.queryForObject(
                "select count(*) from sys_message", Long.class);
        assertThat(messageCountAfter).as("被拒请求不得产生站内信数据").isEqualTo(messageCountBefore);
    }

    // ---------- AC-8：非法令牌 → 401/403 internal 面校验同形，且不产生数据 ----------

    @Test
    void should_reject_with_invalid_token_when_internal_notify_called() throws Exception {
        long messageCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_message", Long.class);

        ObjectNode body = JSON.createObjectNode();
        body.put("sender", SCHEDULER_SENDER);
        body.put("title", "req150756-非法令牌-不得落库");

        MvcResult result = mvc.perform(post(NOTIFY_URL)
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", "invalid-token-req150756")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status).as("非法令牌应被 internal 面拒绝（AC-8 401/403 同形，A-2）").isIn(401, 403);

        long messageCountAfter = jdbcTemplate.queryForObject(
                "select count(*) from sys_message", Long.class);
        assertThat(messageCountAfter).as("被拒请求不得产生站内信数据").isEqualTo(messageCountBefore);
    }

    // ---------- 支撑（照抄范本） ----------

    /** 内部告警调用（A-1：userId=platform-scheduler、租户 claim null，范本 internalCall 口径）。 */
    private JsonNode internalNotify(ObjectNode body) throws Exception {
        MvcResult result = mvc.perform(post(NOTIFY_URL)
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                SCHEDULER_SENDER, null, Channel.B, Set.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "it-stub", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("valid token 不应被安全层拒绝（4xx/5xx 即上报）：%s",
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void seedUser(long id, String username) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false,
                                now(), 'req150756-it', now(), 'req150756-it', 0, false)
                        """,
                id, username, username);
    }

    private long messageCountFor(long recipientId, String title) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_message where recipient_id = ? and title = ?",
                Long.class, recipientId, title);
        return count == null ? 0L : count;
    }

    /** 轮询支撑（范本口径：自旋 + 足够余量，不 sleep 死等）。 */
    private static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return condition.getAsBoolean();
    }
}
