package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.api.AuthErrorCodes;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261119-082531 自助账号安全（platform-auth，/admin/v1/auth/me/*）服务域集成测试
 * （Testcontainers PG+Redis，装配口径照抄 {@code REQ20261117061017OnlineBatchKickIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-3 改密全链（正确旧密码→200＋其他会话 KICKED＋撤销、当前会话仍有效；错误旧密码→
 * OLD_PASSWORD_INVALID 且密码未变；新旧相同→SAME_PASSWORD；新密码违反 8-64 策略→
 * PASSWORD_POLICY_VIOLATED）；AC-4 会话面（本人列表 current 标记、指定会话下线目标 KICKED+撤销
 * 而当前保留、他人会话 NOT_FOUND 语义防探测、下线其他保留当前）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 主体与凭据自置：改密测试不共用 V2/V4 种子 admin 凭据（同 JVM 用例序不定，改密会
 *       互扰登录）——专用主体 userId=930099，凭据行由用例经 JdbcTemplate 预置（BCrypt「OldPass@123」），
 *       改密断言以 BCrypt matches 收口（新口可用/旧口失效/错误路径散列逐字节不变）。</li>
 *   <li>AMB-2 当前会话标识：控制器按确认记录③从 Authorization Bearer 用户令牌自校验取 jti
 *       （LogoutUseCase/AdminRenewUseCase 先例；网关注入的内部令牌 X-Acme-Token jti 为逐请求新签值，
 *       与会话 tokenId 无关）——测试以 {@code UserTokenIssuer.issue} 签发真令牌并以 jti 预置
 *       当前会话底账行（OnlineBatchKick AMB-1 直插口径）。</li>
 *   <li>AMB-3 撤销生效断言：踢下线后 Redis 撤销集须含会话标识（{@code SessionRedis.REVOKE_KEY_PREFIX
 *       + token_id}，OnlineBatchKick AMB-2 口径）；当前会话撤销键不得出现。</li>
 *   <li>AMB-4 免权限点：自助端点无 @RequirePermission（契约确认③④），仅认证即可——
 *       请求经 InternalTokenAuthFilter 携内部令牌（userId=930099）+ Bearer 用户令牌。</li>
 *   <li>AMB-5 错误码断言：OLD_PASSWORD_INVALID=platform-auth-07-0001 / SAME_PASSWORD=
 *       platform-auth-07-0002（07 段，AuthErrorCodes 对齐）；PASSWORD_POLICY_VIOLATED=
 *       platform-auth-02-0002（02 段既有）；SESSION_NOT_FOUND=platform-auth-04-0001（HTTP 200
 *       业务失败信封，04 段既有口径）。</li>
 *   <li>AMB-6 void 端点：DELETE sessions/{id} 与 sessions/others 按契约 emptyAsSuccess 语义
 *       2xx 空体成功（Modules.md 3.1.1 豁免信封）。</li>
 * </ul>
 */
class REQ20261119082531SelfAccountIntegrationTest extends AbstractAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 自助改密专用主体（不共用种子 admin 凭据，AMB-1）。 */
    private static final long SELF_USER_ID = 930099L;

    private static final String OLD_PASSWORD = "OldPass@123";
    private static final String NEW_PASSWORD = "NewPass@456";

    /** 预置会话 token_id（AMB-2 当前会话以外的他人/本人类，teardown 撤销键清理）。 */
    private static final String OTHER_OWN_TOKEN = "req082531-b";
    private static final String VICTIM_TOKEN = "req082531-victim";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier internalTokenVerifier;

    @Autowired
    private InternalTokenIssuer internalTokenIssuer;

    @Autowired
    private UserTokenIssuer userTokenIssuer;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mvc;

    /** 预置行主键单调发生器（同毫秒内多次插入不碰撞；93090000+ 与其他用例段位隔离）。 */
    private final java.util.concurrent.atomic.AtomicLong seedIds =
            new java.util.concurrent.atomic.AtomicLong(93090000L);

    /** 本类签发/预置的会话标识（teardown 撤销键清理）。 */
    private final List<String> sessionTokenIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(internalTokenVerifier, List.of()))
                .build();
        seedCredential();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from auth_session where user_id = ? or token_id like 'req082531-%'",
                SELF_USER_ID);
        jdbcTemplate.update("delete from auth_credential where user_id = ?", SELF_USER_ID);
        for (String tokenId : sessionTokenIds) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + tokenId);
        }
        sessionTokenIds.clear();
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-3：改密全链——正确旧密码 → 200＋其他会话 KICKED+撤销、当前会话仍有效 ----------

    @Test
    void putMePasswordWithCorrectOldPasswordReturnsVoidAndKeepsCurrentSession() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(OTHER_OWN_TOKEN, SELF_USER_ID);
        String hashBefore = credentialHash();

        JsonNode body = changePassword(currentToken, OLD_PASSWORD, NEW_PASSWORD);

        assertThat(body.path("code").asText()).as("改密成功信封 code=0").isEqualTo("0");
        // 凭据已换代：新口可校验、旧口失效（AMB-1 BCrypt 收口）
        String hashAfter = credentialHash();
        assertThat(hashAfter).as("凭据散列应已更新").isNotEqualTo(hashBefore);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, hashAfter)).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, hashAfter)).isFalse();
        // 其他会话：底账 KICKED＋撤销集生效（AMB-3）
        assertThat(sessionStatus(OTHER_OWN_TOKEN)).isEqualTo("KICKED");
        assertThat(revoked(OTHER_OWN_TOKEN)).as("被踢会话撤销键应生效").isTrue();
        // 当前会话保持：底账 ACTIVE 且撤销键不得出现
        assertThat(sessionStatus(currentJti)).as("当前会话底账应保持 ACTIVE").isEqualTo("ACTIVE");
        assertThat(revoked(currentJti)).as("当前会话不得进撤销集").isFalse();
    }

    @Test
    void putMePasswordKicksOtherActiveSessionsAndRevokesThem() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(OTHER_OWN_TOKEN, SELF_USER_ID);
        seedSession("req082531-c", SELF_USER_ID);

        JsonNode body = changePassword(currentToken, OLD_PASSWORD, NEW_PASSWORD);

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(sessionStatus(OTHER_OWN_TOKEN)).isEqualTo("KICKED");
        assertThat(sessionStatus("req082531-c")).isEqualTo("KICKED");
        assertThat(revoked(OTHER_OWN_TOKEN)).isTrue();
        assertThat(revoked("req082531-c")).isTrue();
        assertThat(activeCount(SELF_USER_ID)).as("仅当前会话保持 ACTIVE").isEqualTo(1);
    }

    // ---------- AC-3：错误旧密码 → OLD_PASSWORD_INVALID 且密码未变 ----------

    @Test
    void putMePasswordWithWrongOldPasswordReturnsOldPasswordInvalidAndPasswordUnchanged() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        String hashBefore = credentialHash();

        JsonNode body = changePassword(currentToken, "TotallyWrong@1", NEW_PASSWORD);

        assertThat(body.path("success").asBoolean()).as("错误旧密码应失败").isFalse();
        assertThat(body.path("code").asText())
                .as("OLD_PASSWORD_INVALID 精确码（07 段，AMB-5）")
                .isEqualTo(AuthErrorCodes.OLD_PASSWORD_INVALID);
        assertThat(credentialHash()).as("密码未变（散列逐字节一致）").isEqualTo(hashBefore);
        assertThat(sessionStatus(currentJti)).as("失败路径不得踢任何会话").isEqualTo("ACTIVE");
    }

    // ---------- AC-3：新旧相同 → SAME_PASSWORD ----------

    @Test
    void putMePasswordRejectsSameAsOldPassword() throws Exception {
        String currentToken = issueAndSeedCurrentSession();

        JsonNode body = changePassword(currentToken, OLD_PASSWORD, OLD_PASSWORD);

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText())
                .as("SAME_PASSWORD 精确码（07 段，AMB-5）").isEqualTo(AuthErrorCodes.SAME_PASSWORD);
    }

    // ---------- AC-3：新密码违反 8-64 策略 ----------

    @Test
    void putMePasswordRejectsNewPasswordViolatingPolicy8To64() throws Exception {
        String currentToken = issueAndSeedCurrentSession();

        JsonNode body = changePassword(currentToken, OLD_PASSWORD, "Ab1");

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText())
                .as("PASSWORD_POLICY_VIOLATED 精确码（02 段既有口径）")
                .isEqualTo(AuthErrorCodes.PASSWORD_POLICY_VIOLATED);
    }

    // ---------- AC-4：本人会话列表——仅本人 ACTIVE 行、current 标记正确 ----------

    @Test
    void getMeSessionsListsOnlyOwnActiveWithCurrentMarked() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(OTHER_OWN_TOKEN, SELF_USER_ID);
        seedSession(VICTIM_TOKEN, 3L);

        JsonNode body = JSON.readTree(
                selfPerform(get("/admin/v1/auth/me/sessions"), currentToken)
                        .getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.size()).as("仅本人 ACTIVE 未过期会话（他人行不出现）").isEqualTo(2);
        JsonNode currentRow = null;
        boolean otherMarkedCurrent = false;
        for (JsonNode row : data) {
            if (row.path("tokenId").asText().equals(currentJti)) {
                currentRow = row;
            }
            if (row.path("tokenId").asText().equals(OTHER_OWN_TOKEN)
                    && row.path("current").asBoolean()) {
                otherMarkedCurrent = true;
            }
            assertThat(row.has("ip")).as("wire 字段 ip 存在").isTrue();
            assertThat(row.has("issuedAt")).as("wire 字段 issuedAt 存在").isTrue();
            assertThat(row.has("lastActiveAt")).as("wire 字段 lastActiveAt 存在").isTrue();
        }
        assertThat(currentRow).as("当前令牌对应行应在列表中").isNotNull();
        assertThat(currentRow.path("current").asBoolean())
                .as("仅当前会话标记 current（AMB-2）").isTrue();
        assertThat(otherMarkedCurrent).as("其他会话不得标记 current").isFalse();
    }

    // ---------- AC-4：下线指定会话——目标 KICKED+撤销、当前保留 ----------

    @Test
    void deleteMeSessionByIdKicksTargetAndRevokesWhileCurrentSurvives() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(OTHER_OWN_TOKEN, SELF_USER_ID);

        MvcResult result = selfPerform(
                delete("/admin/v1/auth/me/sessions/" + OTHER_OWN_TOKEN), currentToken);

        assertThat(result.getResponse().getStatus()).as("void 端点 2xx（AMB-6）").isEqualTo(200);
        assertThat(sessionStatus(OTHER_OWN_TOKEN)).isEqualTo("KICKED");
        assertThat(revoked(OTHER_OWN_TOKEN)).isTrue();
        assertThat(sessionStatus(currentJti)).isEqualTo("ACTIVE");
        assertThat(revoked(currentJti)).isFalse();
    }

    // ---------- AC-4：下线他人会话 → NOT_FOUND 语义防探测 ----------

    @Test
    void deleteOtherUsersSessionReturnsNotFoundSemantics() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(VICTIM_TOKEN, 3L);

        JsonNode body = JSON.readTree(selfPerform(
                delete("/admin/v1/auth/me/sessions/" + VICTIM_TOKEN), currentToken)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.path("success").asBoolean()).as("他人会话应拒绝").isFalse();
        assertThat(body.path("code").asText())
                .as("SESSION_NOT_FOUND 精确码（NOT_FOUND 语义防探测，确认记录④）")
                .isEqualTo(AuthErrorCodes.SESSION_NOT_FOUND);
        assertThat(sessionStatus(VICTIM_TOKEN)).as("他人会话不得被踢").isEqualTo("ACTIVE");
        assertThat(revoked(VICTIM_TOKEN)).isFalse();
    }

    // ---------- AC-4：下线其他会话——他人全踢撤销、当前保留 ----------

    @Test
    void deleteMeSessionsOthersKicksOthersKeepsCurrent() throws Exception {
        String currentToken = issueAndSeedCurrentSession();
        seedSession(OTHER_OWN_TOKEN, SELF_USER_ID);
        seedSession("req082531-c", SELF_USER_ID);
        seedSession(VICTIM_TOKEN, 3L);

        MvcResult result = selfPerform(
                delete("/admin/v1/auth/me/sessions/others"), currentToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(sessionStatus(OTHER_OWN_TOKEN)).isEqualTo("KICKED");
        assertThat(sessionStatus("req082531-c")).isEqualTo("KICKED");
        assertThat(revoked(OTHER_OWN_TOKEN)).isTrue();
        assertThat(revoked("req082531-c")).isTrue();
        assertThat(sessionStatus(currentJti)).as("当前会话应保留").isEqualTo("ACTIVE");
        assertThat(revoked(currentJti)).isFalse();
        assertThat(sessionStatus(VICTIM_TOKEN)).as("他人会话不受影响").isEqualTo("ACTIVE");
    }

    // ---------- 支撑 ----------

    /** 当前会话 jti（issue 产生，AMB-2）。 */
    private String currentJti;

    /** 签发当前用户访问令牌并预置当前会话底账行（ACTIVE 未过期），返回 Bearer 原文。 */
    private String issueAndSeedCurrentSession() {
        UserTokenIssuer.IssuedToken issued =
                userTokenIssuer.issue(SELF_USER_ID, "default", Set.of("operator"));
        currentJti = issued.tokenId();
        sessionTokenIds.add(currentJti);
        seedSession(currentJti, SELF_USER_ID);
        return issued.token();
    }

    /** 预置本人凭据行（BCrypt「OldPass@123」；每用例重建避免用例间互扰，AMB-1）。 */
    private void seedCredential() {
        jdbcTemplate.update("""
                insert into auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                                             created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 'default', ?, now(), now(), 'req082531-it', now(), 'req082531-it', 0, false)
                """,
                seedIds.incrementAndGet(), SELF_USER_ID, passwordEncoder.encode(OLD_PASSWORD));
    }

    /** 直插 ACTIVE 未过期会话行（OnlineBatchKick AMB-1 口径）。 */
    private void seedSession(String tokenId, long userId) {
        if (!sessionTokenIds.contains(tokenId)) {
            sessionTokenIds.add(tokenId);
        }
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status,
                                          issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'default', 'B', 'ACTIVE',
                        now() - interval '10 minutes', now() + interval '25 minutes',
                        now(), 'req082531-it', now(), 'req082531-it', 0, false)
                """, seedIds.incrementAndGet(), tokenId, userId);
    }

    /** 自助端点请求构建（内部令牌 userId=930099 ＋ Bearer 当前用户令牌，AMB-4）。 */
    private MockHttpServletRequestBuilder selfCall(MockHttpServletRequestBuilder request, String bearerToken) {
        return request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", internalTokenIssuer.issue(
                        new AuthenticatedSubject(String.valueOf(SELF_USER_ID), "default", Channel.B,
                                Set.of("operator"))))
                .header("Authorization", "Bearer " + bearerToken);
    }

    /** 自助端点请求执行（selfCall + perform）。 */
    private MvcResult selfPerform(MockHttpServletRequestBuilder request, String bearerToken) throws Exception {
        return mvc.perform(selfCall(request, bearerToken)).andReturn();
    }

    /** 改密请求捷径（JSON 体；返回信封 JSON）。 */
    private JsonNode changePassword(String currentToken, String oldPassword, String newPassword)
            throws Exception {
        MvcResult result = mvc.perform(selfCall(put("/admin/v1/auth/me/password"), currentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"oldPassword": "%s", "newPassword": "%s"}
                        """.formatted(oldPassword, newPassword)))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 本人凭据散列（jdbc 原生读取，绕开 mapper 过滤面）。 */
    private String credentialHash() {
        return jdbcTemplate.queryForObject(
                "select password_hash from auth_credential where user_id = ?", String.class, SELF_USER_ID);
    }

    /** 会话状态（token_id 精确行；无行返回 null）。 */
    private String sessionStatus(String tokenId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "select status from auth_session where token_id = ?", String.class, tokenId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** 撤销集是否含会话标识（AMB-3）。 */
    private boolean revoked(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + tokenId));
    }

    /** 本人 ACTIVE 会话计数。 */
    private long activeCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_session where user_id = ? and status = 'ACTIVE'",
                Long.class, userId);
        return count == null ? 0L : count;
    }
}
