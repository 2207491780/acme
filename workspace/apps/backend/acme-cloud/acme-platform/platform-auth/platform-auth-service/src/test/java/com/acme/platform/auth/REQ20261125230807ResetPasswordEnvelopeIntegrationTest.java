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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261125-230807（AC-5 auth 域）重置密码端点信封化 wire 契约测试（platform-auth，Testcontainers
 * PG+Redis，装配口径照抄 {@code AuthSeedIntegrationTest}/{@code REQ20261117025200TenantSessionRevokeIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：Tests run: N, Skipped: 0）。system 域 7 处由
 * {@code REQ20261125230807VoidEndpointsEnvelopeIntegrationTest}（platform-system 模块）钉死。
 *
 * <p>契约（API 节「void 端点信封化」+ 确认记录① + 钉死事实①）：auth 域
 * {@code PUT /admin/v1/auth/credentials/users/{userId}/password}（既有端点，path 不变）改返回
 * {@code Result<Void>}，成功 wire=HTTP 200 + Result 信封 {@code code="0"}（空体为兼容口径，
 * 信封化后不再出现 0 字节体）；业务失败=统一响应体 Result.code≠0（勿断言 4xx）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1（与 system 侧同类，TL 裁决项）：契约多处写「9 个 void 端点」但枚举一致为 8 处 path；
 *       本类按钉死事实①枚举钉死 auth 域 1 处，第 9 处身份待 TL 裁决。</li>
 *   <li>AMB-17 凭据夹具：auth_credential 直插（列面照 V2__seed_superadmin_credential.sql 数据面申报），
 *       password_hash 用 Java BCryptPasswordEncoder(10) 预生成（与 pgcrypto 产物 $2a 前缀兼容，
 *       AuthSeedIntegrationTest Q-D05 核验口径）；user_id 取远端区间避免与种子撞车。</li>
 *   <li>AMB-18 Redis 容器照 TenantSessionRevoke 先例常驻（重置密码若触发会话撤销走 SessionRedis，
 *       FAIL_OPEN 降级两态皆不破坏信封断言）。</li>
 *   <li>AMB-19 密码取值满足应用层 8~64 长度策略（AdminPasswordSetRequest javadoc 口径），
 *       成功路径断言不触策略分支。</li>
 *   <li>AMB-20 权限判定桩：重置用例首步 {@code AuthorizationGateway.requireCredentialManage()} 经
 *       platform-system 实判（Feign），本测试上下文无 platform-system 实例——以 {@code @MockitoBean}
 *       全放行（hasPermission=true + requirePermission 放行，LoginLogQuery/OnlineUsers 同口径）。</li>
 * </ul>
 *
 * <p>delta-r2 复核（2026-11-26）：信封化已落地——CredentialController#resetPassword 返回
 * {@code Result<Void>}，本用例当前预期绿；原「预期红锚点（实现未落地：wire=HTTP 200 + 0 字节体）」
 * 已被实现覆盖。装配失败/夹具 SQL 错才属用例缺陷需自修（首跑 500 根因即装配缺口：漏配
 * AMB-20 权限桩，已补）。</p>
 */
class REQ20261125230807ResetPasswordEnvelopeIntegrationTest extends AbstractAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String RESET_PASSWORD_URL = "/admin/v1/auth/credentials/users/{userId}/password";

    private static final String OLD_PASSWORD = "FixtureOld@2026";
    private static final String NEW_PASSWORD = "NewPass@2026x";

    /** 夹具 user_id 区间（teardown 按 user_id 区间物理清理；远离种子 user_id=1）。 */
    private static final long FIXTURE_USER_ID_BASE = 9230807001L;
    private static final long FIXTURE_USER_ID_TOP = 9230807099L;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 权限判定桩件（AMB-20）：默认全放行（LoginLogQuery/OnlineUsers 先例同款）。 */
    @MockitoBean
    private com.acme.platform.auth.infrastructure.client.AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        allowAllPermissions();
    }

    /** 权限桩全放行（AMB-20）：hasPermission=true + requirePermission 放行（LoginLogQuery 同款）。 */
    private void allowAllPermissions() {
        when(authorizationGateway.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：夹具凭据按 user_id 区间物理清理（种子 user_id=1 不动），清上下文残留
        jdbcTemplate.update("delete from auth_credential where user_id between ? and ?",
                FIXTURE_USER_ID_BASE, FIXTURE_USER_ID_TOP);
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-5（auth 域）：重置密码成功 → HTTP 200 + Result 信封 code="0" ----------

    @Test
    void should_return_result_envelope_when_admin_resets_user_password() throws Exception {
        long userId = insertFixtureCredential();

        ObjectNode body = JSON.createObjectNode();
        body.put("password", NEW_PASSWORD);

        // wire 变更面：PUT /admin/v1/auth/credentials/users/{userId}/password 成功 → 信封（AMB-1）
        MvcResult result = mvc.perform(put(RESET_PASSWORD_URL, userId)
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("重置密码应 HTTP 200").isEqualTo(200);
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).as("重置密码成功 wire=Result 信封（空体=信封化未落地，预期红锚点）").isNotBlank();
        JsonNode envelope = JSON.readTree(content);
        assertThat(envelope.path("success").asBoolean()).as("success=true").isTrue();
        assertThat(envelope.path("code").asText()).as("成功信封 code=\"0\"").isEqualTo("0");

        // 成功语义实证：密码确实变更（BCrypt 可校验新口令，旧口令失效）
        String hash = jdbcTemplate.queryForObject(
                "select password_hash from auth_credential where user_id = ?", String.class, userId);
        assertThat(encoder.matches(NEW_PASSWORD, hash))
                .as("重置后新口令 BCrypt 校验通过").isTrue();
        assertThat(encoder.matches(OLD_PASSWORD, hash))
                .as("重置后旧口令应失效").isFalse();
    }

    // ---------- 夹具与支撑 ----------

    /** 直插夹具凭据（列面照 V2 seed 数据面申报；password_hash Java BCrypt 预生成，AMB-17）。 */
    private long insertFixtureCredential() {
        long userId = FIXTURE_USER_ID_BASE;
        int inserted = jdbcTemplate.update("""
                insert into auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                                             created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 'default', ?, now(), now(), 'req230807-it', now(), 'req230807-it', 0, false)
                """, userId, userId, encoder.encode(OLD_PASSWORD));
        assertThat(inserted).as("前置：夹具凭据应插入").isEqualTo(1);
        return userId;
    }
}
