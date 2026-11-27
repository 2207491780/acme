package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.application.ImageCaptchaUseCase;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.TenantInternalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120105 AC-3/AC-4/AC-5/AC-6（online 侧）：在线用户列表 + 踢下线（platform-auth）
 * 服务域集成测试（Testcontainers PG+Redis，照抄 {@code REQ20261114120104DeptApiIntegrationTest}
 * 装配口径；无 Docker 自动跳过）。
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 会话构造：目标会话经真实 B 端登录（POST /admin/v1/auth/tokens）产生（种子超管凭据，
 *       REQ20261114120105LoginLogIntegrationTest 同口径）；操作者身份经内部令牌 roles 承载
 *       （范本口径，无需真实登录）。</li>
 *   <li>AMB-2 sessionId 映射（TL 裁决确认记录⑦已关闭）：列表项 sessionId = AuthSession token_id，
 *       踢下线按 token_id 撤销；令牌 jti 经 JWS payload 标准 claim 解码（撤销集按 jti 先例，
 *       token_id 与 jti 同源）。</li>
 *   <li>AMB-3 wire 码已钉死（TL 回填，确认记录⑧）：SESSION_NOT_FOUND=platform-auth-04-0001、
 *       REVOKE_SUPERADMIN_FORBIDDEN=platform-auth-04-0002、权限拒绝=platform-system-07-0001
 *       （HTTP 403 + Result 信封）；权限经 AuthorizationGateway 桩件控制（AMB-4）。</li>
 *   <li>AMB-5 「过期会话不出现」（TL 裁决 3①）：主断言改真实过期态——直接插入 expires_at 过去时的
 *       AuthSession 记录（should_excludeExpiredSession_when_queryOnlineUsers）；「被撤销会话不出现」
 *       代理断言保留。</li>
 *   <li>AMB-7 已钉死（TL 回填，确认记录⑧）：表名 auth_session（platform-auth V3），
 *       status='ACTIVE'（终态 'KICKED'）；直插列集兼容；teardown 对该表静默守护。</li>
 *   <li>AMB-6 撤销集生效断言：踢下线后 Redis 撤销集须含该会话 jti（SessionRedis.REVOKE_KEY_PREFIX，
 *       AuthRedisSupportIntegrationTest 先例口径）；网关侧 401 拦截为既有基建（RevocationCheckFilter），
 *       不在本服务域用例范围。</li>
 * </ul>
 */
class REQ20261114120105OnlineUsersIntegrationTest extends AbstractAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    private LoginGuardRedis loginGuard;

    @MockitoBean
    private ImageCaptchaUseCase imageCaptchaUseCase;

    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    /** 租户目录桩（REQ-20261117-025200 确认记录③）：登录第 4.5 步 by-code 实查，default 租户启用未过期。 */
    @MockitoBean
    private TenantDirectoryGateway tenantDirectoryGateway;

    /** 权限判定桩件（AMB-4）：默认全放行；AC-6 用例内改桩全拒绝。 */
    @MockitoBean
    private com.acme.platform.auth.infrastructure.client.AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doNothing().when(imageCaptchaUseCase).verify(anyString(), anyString());
        // 登录画像（单查）；列表 username 解析/超管判定走批量画像（TL 裁决确认记录⑤，backend 交付口径）
        when(authProfileGateway.findByUsername("default", "admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
        // REQ-20261117-025200：登录租户有效期校验数据源——default 租户启用且未过期
        when(tenantDirectoryGateway.findByCode("default")).thenReturn(
                new TenantInternalView("default", "默认租户", "ENABLED", null));
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1L, new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin"))));
        when(authorizationGateway.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：登录日志 + 过期态种子会话 + 防爆破计数 + 已知会话/撤销键（本类独立容器，库内残留随容器销毁）
        jdbcTemplate.update("delete from sys_login_log");
        // auth_session 表名已钉死（platform-auth V3，确认记录⑧）：teardown 无需静默守护
        jdbcTemplate.update("delete from auth_session where token_id like 'req120105-%'");
        loginGuard.clearFailures("admin");
        String jti = sessionRedis().currentSessionTokenId(1L);
        if (jti != null) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-3：活跃会话列表与会话一致（登录后出现，username/ip/登录时间/最近活跃/userType） ----------

    @Test
    void should_listActiveSession_when_adminLoggedIn() {
        String jti = adminLogin();

        JsonNode body = call(get("/admin/v1/auth/online-users"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode item = findByUsername(recordsOf(body), "admin");
        assertThat(item).as("登录后的活跃会话应出现在在线用户列表").isNotNull();
        if (item != null) {
            assertThat(sessionIdOf(item)).as("列表项应含会话标识（sessionId/id，AMB-2）").isNotBlank();
            assertThat(item.path("ip").asText()).as("列表项含 ip").isNotBlank();
            assertThat(item.path("userType").asText()).as("列表项含 userType（AMB wire=B）").isEqualTo("B");
            // 契约 §API + 确认记录⑥（TL 2026-11-15 澄清）：「最近活跃」=每请求活跃（网关按请求更新
            // 会话信息 lastActiveAt）；断言仅钉字段存在性与非空，不绑定续约/刷新触发点（字段名 AMB-2）
            assertThat(item.path("lastActiveAt").asText("")).as("列表项含最近活跃时间（来源=会话信息）").isNotBlank();
            assertThat(item.path("loginAt").asText("")).as("列表项含登录时间").isNotBlank();
        }
        cleanupSessionKeys(jti);
    }

    // ---------- AC-3：过期会话不出现（真实过期态：AuthSession expires_at 过去时，TL 裁决 3①） ----------

    @Test
    void should_excludeExpiredSession_when_queryOnlineUsers() {
        String jti = adminLogin(); // 保证列表非空且端点可用

        // 直接插入 expires_at 为过去时的会话记录（AuthSession 列集=TL 裁决 3①：
        // token_id/user_id/tenant_id/channel/status/issued_at/expires_at；表名 auth_session 与
        // status 字面量 'ACTIVE' 为实现惯例推定——AMB-7，交付对齐时回填）
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status, issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (920001, 'req120105-expired-token', 1, 'default', 'B', 'ACTIVE',
                        now() - interval '1 hour', now() - interval '1 minute',
                        now(), 'system', now(), 'system', 0, false)
                """);

        JsonNode body = call(get("/admin/v1/auth/online-users"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(sessionIdsOf(recordsOf(body)))
                .as("expires_at 过去时的会话不应出现在在线用户列表（AC-3 过期会话不出现）")
                .doesNotContain("req120105-expired-token");
        cleanupSessionKeys(jti);
    }

    // ---------- AC-3/AC-4：踢下线生效（列表移除 + 撤销集写入），超管互踢允许 ----------

    @Test
    void should_removeKickedSessionAndWriteRevocation_when_superAdminKicks() {
        String jti = adminLogin();
        JsonNode before = call(get("/admin/v1/auth/online-users"));
        String sessionId = sessionIdOf(findByUsername(recordsOf(before), "admin"));
        assertThat(sessionId).as("被踢会话应先存在于列表").isNotBlank();

        // 操作者=超管（内部令牌 super_admin 角色）：踢目标为超管的会话 → 超管互踢允许
        JsonNode kicked = call(delete("/admin/v1/auth/online-users/{sessionId}", sessionId),
                Set.of("super_admin"));

        assertThat(kicked.path("code").asText()).as("超管互踢应允许（AC-5 正向）").isEqualTo("0");

        JsonNode after = call(get("/admin/v1/auth/online-users"));
        assertThat(findByUsername(recordsOf(after), "admin") == null
                || !sessionIdOf(findByUsername(recordsOf(after), "admin")).equals(sessionId))
                .as("被踢会话不应再出现在在线用户列表（AC-3 非活跃不出现，AMB-5 代理口径）").isTrue();
        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + jti))
                .as("踢下线应写撤销集（AC-4 后端语义，AMB-6）").isTrue();
        cleanupSessionKeys(jti);
    }

    // ---------- AC-4 边界：互踢（再次登录）旧会话写撤销集 ----------

    @Test
    void should_revokePreviousSession_when_adminRelogin() {
        String oldJti = adminLogin();
        String newJti = adminLogin();

        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + oldJti))
                .as("互踢后旧会话令牌应入撤销集（下次访问被网关拦截，AC-4）").isTrue();
        assertThat(newJti).isNotEqualTo(oldJti);
        cleanupSessionKeys(oldJti);
        cleanupSessionKeys(newJti);
    }

    // ---------- 边界：目标会话不存在 → SESSION_NOT_FOUND（业务码≠0，wire 值 AMB-3） ----------

    @Test
    void should_rejectKickWithBusinessCode_when_targetSessionMissing() {
        JsonNode rejected = call(delete("/admin/v1/auth/online-users/{sessionId}", "req120105-no-such-session"),
                Set.of("super_admin"));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("SESSION_NOT_FOUND 精确码（确认记录⑧，业务失败勿断言 4xx）")
                .isEqualTo("platform-auth-04-0001");
    }

    // ---------- AC-5：非超管踢超管会话 → REVOKE_SUPERADMIN_FORBIDDEN（业务码≠0，wire 值 AMB-3） ----------

    @Test
    void should_forbidNonSuperAdminKickingSuperAdminSession_when_kick() {
        String jti = adminLogin();
        JsonNode before = call(get("/admin/v1/auth/online-users"));
        String sessionId = sessionIdOf(findByUsername(recordsOf(before), "admin"));

        // 操作者=非超管（内部令牌 ops 角色，userId=2）：目标为超管会话
        JsonNode rejected = call(delete("/admin/v1/auth/online-users/{sessionId}", sessionId), Set.of("ops"));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("REVOKE_SUPERADMIN_FORBIDDEN 精确码（确认记录⑧）")
                .isEqualTo("platform-auth-04-0002");
        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + jti))
                .as("拒绝后不得写撤销集").isFalse();
        cleanupSessionKeys(jti);
    }

    // ---------- AC-6：无权限点 → 三端点统一拒绝（Result.code≠0，勿断言 4xx） ----------

    @Test
    void should_rejectAllAuthSecurityEndpoints_when_noPermission() {
        when(authorizationGateway.hasPermission(anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new BizException(new PermissionDenied()))
                .when(authorizationGateway).requirePermission(anyString());

        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                get("/admin/v1/auth/login-logs"),
                get("/admin/v1/auth/online-users"),
                delete("/admin/v1/auth/online-users/{sessionId}", "req120105-any-session"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = call(endpoint, Set.of("super_admin"));
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint).isFalse();
            assertThat(body.path("code").asText())
                    .as("权限拒绝精确码（确认记录⑧，HTTP 403 + Result 信封 code≠0，勿断言 4xx）：%s", endpoint)
                    .isEqualTo("platform-system-07-0001");
        }
    }

    /** 镜像 AuthorizationGateway.CrossDomainPermissionDenied（私有不可见）：码归 system 段 + HTTP 403。 */
    private static final class PermissionDenied implements ErrorCode, HttpStatusAware {
        @Override
        public String code() {
            return SystemErrorCodes.PERMISSION_DENIED;
        }

        @Override
        public String defaultMessage() {
            return "无操作权限";
        }

        @Override
        public int httpStatus() {
            return 403;
        }
    }

    // ---------- 支撑 ----------

    /** 真实 B 端登录产生会话（AMB-1），返回访问令牌 jti（AMB-2）。 */
    private String adminLogin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", "Admin@123456");
        body.put("captchaId", "req120105-captcha-id");
        body.put("captchaCode", "a1b2");
        JsonNode issued = call(post("/admin/v1/auth/tokens")
                .header("X-Acme-Tenant", "default")
                .header("X-Acme-Channel", "B")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(issued.path("code").asText()).as("B 端登录应成功").isEqualTo("0");
        String accessToken = issued.path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return jtiOf(accessToken);
    }

    /** JWS payload 标准 claim jti 解码（AMB-2：AdminTokenIssued 契约 JWS HS256）。 */
    private static String jtiOf(String accessToken) {
        String[] parts = accessToken.split("\\.");
        assertThat(parts.length).as("访问令牌应为 JWS 三段式").isEqualTo(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String jti = JSON.readTree(payload).path("jti").asText(null);
        assertThat(jti).as("令牌 payload 应含 jti claim").isNotBlank();
        return jti;
    }

    /** 分页数据定位：data 数组 / {records} 双兼容。 */
    private static JsonNode recordsOf(JsonNode body) {
        JsonNode data = body.path("data");
        return data.isArray() ? data : data.path("records");
    }

    private static JsonNode findByUsername(JsonNode records, String username) {
        for (JsonNode item : records) {
            if (username.equals(item.path("username").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 会话标识字段读取（TL 裁决：sessionId = AuthSession token_id，踢下线按 token_id 撤销；id 兜底）。 */
    private static String sessionIdOf(JsonNode item) {
        if (item == null) {
            return null;
        }
        if (item.hasNonNull("sessionId")) {
            return item.get("sessionId").asText();
        }
        return item.hasNonNull("id") ? item.get("id").asText() : null;
    }

    private static java.util.List<String> sessionIdsOf(JsonNode records) {
        return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(REQ20261114120105OnlineUsersIntegrationTest::sessionIdOf)
                .toList();
    }

    private SessionRedis sessionRedis() {
        // SessionRedis 为真实组件（撤销集/会话索引断言与清理均经其公开常量与 API）
        return webContext.getBean(SessionRedis.class);
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) {
        return call(request, Set.of("super_admin"));
    }

    /** call(request, roles)：操作者身份经内部令牌 roles 承载（范本口径）；默认超管。 */
    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request, Set<String> roles) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject("1", "default", Channel.B, roles)))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    private void cleanupSessionKeys(String jti) {
        if (jti != null) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
    }
}
