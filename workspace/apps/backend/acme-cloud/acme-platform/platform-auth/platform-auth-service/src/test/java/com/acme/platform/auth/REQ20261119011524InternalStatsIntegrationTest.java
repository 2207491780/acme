package com.acme.platform.auth;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261119-011524 统计内部接口（platform-auth）服务域集成测试（Testcontainers PG+Redis，
 * 装配口径照抄 {@code REQ20261117025200TenantSessionRevokeIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖 AC-1（auth 半边，服务身份调用免权限点）：
 * GET /internal/v1/online-count（ACTIVE 且未过期计数——过期/终态会话不计入）；
 * GET /internal/v1/login-log-stats（sys_login_log 按日聚合 channel 不分、窗口外不计、软删行不计、
 * 按日升序；无日志日不出行）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 日期桶标签以 PG 会话时区 to_char 为准（聚合 SQL 同源），断言用相对标签
 *       （today / yesterday 经 DB now() 折算）避免 JVM/容器时区分歧。</li>
 *   <li>AMB-2 会话底账直插列集照抄 {@code REQ20261117025200TenantSessionRevokeIntegrationTest}
 *       先例；登录日志直插（created_at 显式指定日期），软删行经 deleted=true 直插。</li>
 *   <li>AMB-3 调用方身份模拟 platform-system 服务主体（Q-D08：内部令牌 userId=服务名）；
 *       internal 端点无权限点，roles 仅随令牌装配。</li>
 * </ul>
 */
class REQ20261119011524InternalStatsIntegrationTest extends AbstractAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TOKEN_S1 = "req011524-s1";
    private static final String TOKEN_S2 = "req011524-s2";
    private static final String TOKEN_EXPIRED = "req011524-expired";
    private static final String TOKEN_LOGOUT = "req011524-logout";

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
        cleanSeeds();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown（本类独立容器，残留随容器销毁兜底）
        cleanSeeds();
    }

    private void cleanSeeds() {
        jdbcTemplate.update("delete from auth_session where token_id like 'req011524-%'");
        jdbcTemplate.update("delete from sys_login_log where username like 'req011524-%'");
    }

    // ---------- AC-1：online-count 仅计 ACTIVE 且未过期会话 ----------

    @Test
    void should_countOnlyActiveNotExpiredSessions_when_onlineCountCalled() {
        seedActiveSession(91152401L, TOKEN_S1);
        seedActiveSession(91152402L, TOKEN_S2);
        seedExpiredSession(91152403L, TOKEN_EXPIRED);
        seedTerminalSession(91152404L, TOKEN_LOGOUT, "LOGOUT");

        JsonNode body = callInternal("/internal/v1/online-count");

        assertThat(body.path("code").asText()).as("internal 统计端点恒 200 信封").isEqualTo("0");
        // data 为裸数字（Long wire 为字符串，确认记录① wire Long→string）
        assertThat(body.path("data").asText()).as("ACTIVE 且未过期会话计数").isEqualTo("2");
    }

    @Test
    void should_returnZero_when_noActiveSessions() {
        JsonNode body = callInternal("/internal/v1/online-count");

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").asText()).as("无活跃会话幂等返回 0").isEqualTo("0");
    }

    // ---------- AC-1：login-log-stats 按日聚合（channel 不分、窗口过滤、升序、软删不计） ----------

    @Test
    void should_aggregateDailyLoginLogCounts_when_statsCalled() {
        // 今日 2 条（B/C 双端 channel 不分）+ 昨日 1 条；窗口外（30 日前）1 条不计；软删 1 条不计
        seedLoginLog("req011524-u-a", "B", "SUCCESS", "now()");
        seedLoginLog("req011524-u-b", "C", "SUCCESS", "now()");
        seedLoginLog("req011524-u-c", "B", "FAILURE", "now() - interval '1 day'");
        seedLoginLog("req011524-u-d", "B", "SUCCESS", "now() - interval '30 day'");
        seedLoginLog("req011524-u-e", "B", "SUCCESS", "now()", true);

        JsonNode body = callInternal("/internal/v1/login-log-stats?days=14");

        assertThat(body.path("code").asText()).isEqualTo("0");
        List<Map<String, String>> points = readPoints(body.path("data"));
        assertThat(points).as("今日+昨日两桶（无日志日不出行）").hasSize(2);
        assertThat(points.get(0).get("date"))
                .as("按日升序（首桶=昨日）").isEqualTo(pgDayOffset("yesterday"));
        assertThat(points.get(0).get("count")).isEqualTo("1");
        assertThat(points.get(1).get("date")).as("末桶=今日").isEqualTo(pgDayOffset("today"));
        assertThat(points.get(1).get("count")).as("channel 不分全量计数").isEqualTo("2");
    }

    @Test
    void should_excludeOutsideWindow_when_daysNarrowed() {
        seedLoginLog("req011524-u-a", "B", "SUCCESS", "now()");
        seedLoginLog("req011524-u-b", "B", "SUCCESS", "now() - interval '2 day'");

        JsonNode body = callInternal("/internal/v1/login-log-stats?days=1");

        List<Map<String, String>> points = readPoints(body.path("data"));
        assertThat(points).as("days=1 窗口仅含今日").hasSize(1);
        assertThat(points.get(0).get("date")).isEqualTo(pgDayOffset("today"));
        assertThat(points.get(0).get("count")).isEqualTo("1");
    }

    @Test
    void should_returnEmptyList_when_noLogInWindow() {
        JsonNode body = callInternal("/internal/v1/login-log-stats?days=14");

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").isArray()).isTrue();
        assertThat(body.path("data").size()).as("窗口内无日志 → 空列表").isZero();
    }

    // ---------- 支撑 ----------

    /** 直插 ACTIVE 底账（expires_at 未来时=未过期）。 */
    private void seedActiveSession(long id, String tokenId) {
        insertSession(id, tokenId, "ACTIVE", "now() + interval '25 minutes'");
    }

    /** 直插已过期 ACTIVE 底账（expires_at 过去时=不计入在线数）。 */
    private void seedExpiredSession(long id, String tokenId) {
        insertSession(id, tokenId, "ACTIVE", "now() - interval '5 minutes'");
    }

    private void seedTerminalSession(long id, String tokenId, String status) {
        insertSession(id, tokenId, status, "now() + interval '25 minutes'");
    }

    private void insertSession(long id, String tokenId, String status, String expiresAtExpression) {
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status,
                                          issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 911524, 'default', 'B', ?, now(), %s,
                        now(), 'req011524-it', now(), 'req011524-it', 0, false)
                """.formatted(expiresAtExpression), id, tokenId, status);
    }

    /** 直插登录日志（created_at 表达式 + 软删标记；channel 不分覆盖 B/C）。 */
    private void seedLoginLog(String username, String userType, String result,
                              String createdAtExpression) {
        seedLoginLog(username, userType, result, createdAtExpression, false);
    }

    private void seedLoginLog(String username, String userType, String result,
                              String createdAtExpression, boolean deleted) {
        jdbcTemplate.update("""
                insert into sys_login_log (id, username, user_type, ip, result,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, '127.0.0.1', ?,
                        %s, 'req011524-it', now(), 'req011524-it', 0, ?)
                """.formatted(createdAtExpression),
                nextLogId(), username, userType, result, deleted);
    }

    /** 测试内自增 id（雪花主键无序列，直插手工取号）。 */
    private long nextLogId() {
        return 911524_00000L + LOG_ID_SEQUENCE.incrementAndGet();
    }

    private static final java.util.concurrent.atomic.AtomicLong LOG_ID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /** DB 时区同源的相对日期桶标签（AMB-1：to_char(now()) 聚合同源，避免 JVM/容器时区分歧）。 */
    private String pgDayOffset(String offset) {
        String expression = switch (offset) {
            case "today" -> "now()";
            case "yesterday" -> "now() - interval '1 day'";
            default -> throw new IllegalArgumentException("unsupported offset: " + offset);
        };
        return jdbcTemplate.queryForObject(
                "select to_char(" + expression + ", 'YYYY-MM-DD')", String.class);
    }

    /** internal 调用（platform-system 服务主体，Q-D08，AMB-3）；业务语义恒 HTTP 200 信封。 */
    private JsonNode callInternal(String path) {
        MvcResult result;
        try {
            result = mvc.perform(get(path)
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                    "platform-system", "default", Channel.B, Set.of())))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("internal 统计调用失败：path=" + path, ex);
        }
        assertThat(result.getResponse().getStatus())
                .as("internal 统计端点恒 200 信封（勿 4xx）").isEqualTo(200);
        try {
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("响应解析失败：path=" + path, ex);
        }
    }

    /** 统计点列表读取（data 数组 → [{date,count}] 有序 Map 列表）。 */
    private static List<Map<String, String>> readPoints(JsonNode data) {
        List<Map<String, String>> points = new ArrayList<>();
        for (JsonNode point : data) {
            points.add(Map.of(
                    "date", point.path("date").asText(),
                    "count", point.path("count").asText()));
        }
        return points;
    }
}
