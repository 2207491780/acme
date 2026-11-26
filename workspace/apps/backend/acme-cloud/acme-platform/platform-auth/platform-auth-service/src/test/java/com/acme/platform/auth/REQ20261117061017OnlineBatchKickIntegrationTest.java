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
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-061017 批量下线 + 登录日志导出（platform-auth）服务域集成测试
 * （Testcontainers PG+Redis，装配口径照抄 {@code REQ20261117011514LoginCaptchaPolicyIntegrationTest}，
 * 会话/撤销集口径照抄 {@code REQ20261114120105OnlineUsersIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-7（DELETE /admin/v1/auth/online-users?username= 批量下线——非超管会话全 kick 且撤销集
 * 生效、超管会话跳过计数、返回 BatchKickView(kickedCount, skippedCount)、缺参/缺权限拒绝）；
 * AC-6（auth 侧）login-logs/export 下载语义。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 会话预置：B 端登录为互踢（单会话）策略（OnlineUsers 用例 {@code should_revokePreviousSession_when_adminRelogin}
 *       证实），「同 username 多会话」无法经重复登录产生——按 OnlineUsers AMB-5/AMB-7 先例直接插入
 *       {@code auth_session}（token_id 唯一、status='ACTIVE'、expires_at 未过期）预置多会话；
 *       底账 {@code findActiveNotExpiredBefore} 全量语义下列表/kick 均作用于这些行。</li>
 *   <li>AMB-2 撤销集生效断言：kick 后 Redis 撤销集须含会话标识（{@code SessionRedis.REVOKE_KEY_PREFIX +
 *       token_id}，token_id 与 jti 同源，OnlineUsers AMB-2/AMB-6 口径）。</li>
 *   <li>AMB-3 超管保护按目标会话用户的角色判定：经 {@code AuthProfileGateway} 桩表达——admin
 *       (user_id=1, super_admin) 会话全跳过（kicked=0/skipped=N 且不写撤销集），demo_operator
 *       (user_id=3, 非 super_admin 角色) 会话全 kick。契约「username 含超管会话」的混合语义在同一
 *       username 维度不可构造（单 username 单用户），以「普通用户全 kick + 超管用户全 skip」两用例
 *       拼接覆盖 AC-7 全部计数分支。</li>
 *   <li>AMB-4 {@code BatchKickView(kickedCount, skippedCount)} 为契约固定签名；wire 字段名双兼容取值
 *       （kickedCount/skippedCount 或下划线变体）。未知 username 按「枚举空集」语义断言 code=0 且
 *       计数 0/0；若交付为 USER_NOT_FOUND 业务失败，本用例同步调整。</li>
 *   <li>AMB-5 username 必填（query 参数）：缺失/空白按参数错误 HTTP 400（captcha-policy 空白参数
 *       400 先例，REQ20261117011514LoginCaptchaPolicyIntegrationTest）。</li>
 *   <li>AMB-6 权限拒绝：auth:online:revoke / auth:loginlog:list 经 {@code AuthorizationGateway}
 *       桩控制，拒绝镜像 {@code platform-system-07-0001}（HTTP 403 + Result 信封，
 *       OnlineUsers AC-6 精确码先例）。</li>
 *   <li>AMB-7 登录日志为异步落库（LoginLogRecorder 先例）：导出行数断言前经轮询等待落库；
 *       导出经真实登录产生行集（V2/V4 种子凭据 + test resources placeholder 密码）。</li>
 *   <li>AMB-8 导出行数=xlsx worksheet 行元素数−表头 1 行（StreamExcelWriter「表头先行」先例）；
 *       X-Export-Truncated 非截断场景不断言为真值。username 筛选参数名取列表页同名筛选先例。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req061017-kick-secret-0123456789abcd",
        "acme.auth.token.hmac-secret=req061017-kick-secret-0123456789abcd"
})
class REQ20261117061017OnlineBatchKickIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 登录日志异步落库轮询上限（LoginLogRecorder 异步口径）。 */
    private static final Duration ASYNC_SETTLE = Duration.ofSeconds(10);

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String OPERATOR_USERNAME = "demo_operator";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：会话撤销集（REVOKE_KEY_PREFIX）真实语义需要。 */
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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LoginGuardRedis loginGuard;

    @MockitoBean
    private ImageCaptchaUseCase imageCaptchaUseCase;

    /** 画像桩：username 解析 + 目标用户角色（超管保护判定数据源，AMB-3）。 */
    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    @MockitoBean
    private TenantDirectoryGateway tenantDirectoryGateway;

    /** 权限判定桩件（AMB-6）：默认全放行；缺权限用例内切换为拒绝。 */
    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    /** 本类登录签发的 jti（teardown 撤销键清理）。 */
    private final List<String> issuedJtis = new ArrayList<>();

    /** 本类预置的会话 token_id（=jti，AMB-2；teardown 撤销键清理）。 */
    private final List<String> seededTokenIds =
            List.of("req061017-op-1", "req061017-op-2", "req061017-sa-1", "req061017-sa-2");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // 方法间隔离（JVM 内方法序非声明序）：本类 login() 会产生真实会话底账（UUID token_id，
        // AfterEach 的 req061017 前缀清理不覆盖）——批量下线按 username 枚举 ACTIVE 集合，
        // 残留行会污染 kicked/skipped 计数断言，故每用例前清空会话表与单会话索引
        jdbcTemplate.update("delete from auth_session");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "3");
        doNothing().when(imageCaptchaUseCase).verify(anyString(), anyString());
        when(authProfileGateway.findByUsername("default", "admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
        when(authProfileGateway.findByUsername("default", OPERATOR_USERNAME)).thenReturn(
                new AuthProfileView("3", OPERATOR_USERNAME, "演示操作员", "default", "ENABLED", Set.of("operator")));
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1L, new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")),
                3L, new AuthProfileView("3", OPERATOR_USERNAME, "演示操作员", "default", "ENABLED", Set.of("operator"))));
        // REQ-20261117-025200：登录租户有效期校验数据源——default 租户启用且未过期
        when(tenantDirectoryGateway.findByCode("default")).thenReturn(
                new TenantInternalView("default", "默认租户", "ENABLED", null));
        when(authorizationGateway.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
        issuedJtis.clear();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：登录日志 + 会话底账（含 login 真实行）+ 防爆破计数 + 撤销键/会话索引（本类独立容器，库内残留随容器销毁）
        jdbcTemplate.update("delete from sys_login_log");
        jdbcTemplate.update("delete from auth_session");
        loginGuard.clearFailures("admin");
        for (String tokenId : seededTokenIds) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + tokenId);
        }
        for (String jti : issuedJtis) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "3");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-7：非超管 username 批量下线——多会话全 kick、撤销集生效、计数 kicked ----------

    @Test
    void should_kickAllActiveSessionsAndWriteRevocation_when_batchKickNonSuperAdminUsername() {
        seedSession(930001, "req061017-op-1", 3L);
        seedSession(930002, "req061017-op-2", 3L);
        seedSession(930003, "req061017-sa-1", 1L);

        JsonNode body = call(delete("/admin/v1/auth/online-users").queryParam("username", OPERATOR_USERNAME));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(longOf(data, "kickedCount", "kicked_count"))
                .as("非超管全部 ACTIVE 会话应被 kick（AMB-4 双兼容）").isEqualTo(2L);
        assertThat(longOf(data, "skippedCount", "skipped_count")).isZero();

        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + "req061017-op-1"))
                .as("被 kick 会话 1 的撤销键应生效（AMB-2）").isTrue();
        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + "req061017-op-2"))
                .as("被 kick 会话 2 的撤销键应生效（AMB-2）").isTrue();
        assertThat(activeSessionCount(3L)).as("demo_operator 会话不应再 ACTIVE").isZero();
        assertThat(kickedSessionCount(3L)).as("终态应为 KICKED（OnlineUsers AMB-7 先例）").isEqualTo(2L);
        // 传入范围隔离：未被点名 username 的会话不受影响
        assertThat(activeSessionCount(1L)).as("admin 会话不应被误 kick").isEqualTo(1L);
        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + "req061017-sa-1")).isFalse();
    }

    // ---------- AC-7：超管 username 批量下线——会话全跳过、计数 skipped、不写撤销集 ----------

    @Test
    void should_skipAllSessionsAndCountSkipped_when_batchKickSuperAdminUsername() {
        seedSession(930011, "req061017-sa-1", 1L);
        seedSession(930012, "req061017-sa-2", 1L);
        seedSession(930013, "req061017-op-1", 3L);

        // 操作者必须为非超管（OnlineSessionUseCase 语义：超管会话跳过仅对非超管操作者生效，
        // 超管操作者批量下线不受保护拦截）——以 demo_operator 身份承载内部令牌
        JsonNode body = callAsOperator(delete("/admin/v1/auth/online-users").queryParam("username", "admin"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(longOf(data, "kickedCount", "kicked_count")).isZero();
        assertThat(longOf(data, "skippedCount", "skipped_count"))
                .as("超管会话应跳过并计入 skipped（AMB-3）").isEqualTo(2L);

        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + "req061017-sa-1"))
                .as("超管会话跳过不得写撤销集").isFalse();
        assertThat(redisTemplate.hasKey(SessionRedis.REVOKE_KEY_PREFIX + "req061017-sa-2")).isFalse();
        assertThat(activeSessionCount(1L)).as("超管会话应保持 ACTIVE").isEqualTo(2L);
        // 传入范围隔离：未被点名 username 的会话不受影响
        assertThat(activeSessionCount(3L)).isEqualTo(1L);
    }

    // ---------- AC-7 边界：未知 username → 空集语义 0/0（AMB-4） ----------

    @Test
    void should_returnZeroCounts_when_usernameHasNoActiveSession() {
        JsonNode body = call(delete("/admin/v1/auth/online-users")
                .queryParam("username", "req061017-ghost"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(longOf(body.path("data"), "kickedCount", "kicked_count")).isZero();
        assertThat(longOf(body.path("data"), "skippedCount", "skipped_count")).isZero();
    }

    // ---------- AC-7 边界：username 必填（缺失/空白 → HTTP 400，AMB-5） ----------

    @Test
    void should_rejectBlankUsername_when_queryParamMissingOrBlank() throws Exception {
        MvcResult missing = rawCall(delete("/admin/v1/auth/online-users"));
        assertThat(missing.getResponse().getStatus())
                .as("username 必填：缺失应参数错误 400（AMB-5）").isEqualTo(400);

        MvcResult blank = rawCall(delete("/admin/v1/auth/online-users").queryParam("username", ""));
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- AC-7/AC-6：缺权限 → 权限拒绝码（批量下线 + 登录日志查询/导出） ----------

    @Test
    void should_rejectWithPermissionDenied_when_noRevokePermission() {
        when(authorizationGateway.hasPermission(anyString())).thenReturn(false);
        doThrow(new BizException(new PermissionDenied())).when(authorizationGateway).requirePermission(anyString());

        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                delete("/admin/v1/auth/online-users").queryParam("username", OPERATOR_USERNAME),
                get("/admin/v1/auth/login-logs"),
                get("/admin/v1/auth/login-logs/export"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = call(endpoint);
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint).isFalse();
            assertThat(body.path("code").asText())
                    .as("权限拒绝精确码（HTTP 403 + Result 信封，OnlineUsers AC-6 先例，AMB-6）：%s", endpoint)
                    .isEqualTo("platform-system-07-0001");
        }
    }

    // ---------- AC-6：login-logs/export 下载语义（xlsx 附件 + 行数随登录产生） ----------

    @Test
    void should_exportLoginLogsAsXlsxAttachment_when_adminDownloads() throws Exception {
        String jti1 = adminLogin();
        String jti2 = adminLogin();
        assertThat(jti2).isNotEqualTo(jti1);

        awaitTrue(ASYNC_SETTLE, () -> loginLogCount() >= 2);

        MvcResult result = rawCall(get("/admin/v1/auth/login-logs/export"));

        long expected = loginLogCount() + 1; // 数据行 + 表头行（AMB-8）
        assertThat(result.getResponse().getContentType()).contains(XLSX_MIME);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("filename*=UTF-8''");
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).as("xlsx 应为 zip 容器（PK 魔数）").isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        assertThat(xlsxRowCount(bytes)).as("导出行数应与落库行数一致").isEqualTo(expected);
        assertThat(result.getResponse().getHeader("X-Export-Truncated")).isNotEqualTo("true");

        // 筛选随行导出（username=admin）：行数与同名筛选行集一致（参数名 AMB-8）
        MvcResult filtered = rawCall(get("/admin/v1/auth/login-logs/export").param("username", "admin"));
        long expectedFiltered = adminLoginLogCount() + 1;
        assertThat(xlsxRowCount(filtered.getResponse().getContentAsByteArray())).isEqualTo(expectedFiltered);
    }

    // ---------- 支撑 ----------

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

    /** 直插 ACTIVE 未过期会话（AMB-1：登录互踢策略下多会话仅可经底账预置产生）。 */
    private void seedSession(long id, String tokenId, long userId) {
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status,
                                          issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'default', 'B', 'ACTIVE',
                        now() - interval '10 minutes', now() + interval '25 minutes',
                        now(), 'system', now(), 'system', 0, false)
                """, id, tokenId, userId);
    }

    private long activeSessionCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_session where user_id = ? and status = 'ACTIVE' "
                        + "and token_id like 'req061017-%'",
                Long.class, userId);
        return count == null ? 0L : count;
    }

    private long kickedSessionCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_session where user_id = ? and status = 'KICKED' "
                        + "and token_id like 'req061017-%'",
                Long.class, userId);
        return count == null ? 0L : count;
    }

    private long loginLogCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_login_log", Long.class);
        return count == null ? 0L : count;
    }

    private long adminLoginLogCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_login_log where username = 'admin'", Long.class);
        return count == null ? 0L : count;
    }

    /** 真实 B 端登录产生登录日志行（AMB-7），返回访问令牌 jti（OnlineUsers AMB-2 口径）。 */
    private String adminLogin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", "Admin@123456");
        body.put("captchaId", "req061017-captcha-id");
        body.put("captchaCode", "a1b2");
        JsonNode issued = call(post("/admin/v1/auth/tokens")
                .header("X-Acme-Tenant", "default")
                .header("X-Acme-Channel", "B")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(issued.path("code").asText()).as("B 端登录应成功").isEqualTo("0");
        String accessToken = issued.path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        String jti = jtiOf(accessToken);
        issuedJtis.add(jti);
        return jti;
    }

    /** JWS payload 标准 claim jti 解码（OnlineUsers AMB-2 先例）。 */
    private static String jtiOf(String accessToken) {
        String[] parts = accessToken.split("\\.");
        assertThat(parts.length).as("访问令牌应为 JWS 三段式").isEqualTo(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String jti = JSON.readTree(payload).path("jti").asText(null);
        assertThat(jti).as("令牌 payload 应含 jti claim").isNotBlank();
        return jti;
    }

    /** wire 字段双兼容取值（驼峰/下划线）。 */
    private static long longOf(JsonNode node, String camel, String snake) {
        if (node.hasNonNull(camel)) {
            return node.get(camel).asLong();
        }
        if (node.hasNonNull(snake)) {
            return node.get(snake).asLong();
        }
        return -1L;
    }

    /** xlsx 行元素计数（按 central directory 读 worksheet XML，不引新依赖）。
     *  不用 ZipInputStream：FastExcel/POI 写出的条目带 data descriptor（local header size=0、
     *  flags=0x8），JDK 25 ZipInputStream 对该布局误报 invalid entry size——产物本身
     *  Excel/ZipFile 均可正常打开，属流式解析器兼容面缺陷，故走临时文件 + ZipFile 解析。 */
    private static int xlsxRowCount(byte[] bytes) throws IOException {
        int rows = 0;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("req061017-export", ".xlsx");
        try {
            java.nio.file.Files.write(temp, bytes);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(temp.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml")) {
                        String xml = new String(zip.getInputStream(entry).readAllBytes(),
                                StandardCharsets.UTF_8);
                        int index = 0;
                        while ((index = xml.indexOf("<row", index)) >= 0) {
                            rows++;
                            index += 4;
                        }
                    }
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
        assertThat(rows).as("xlsx 应含 worksheet 行").isGreaterThan(0);
        return rows;
    }

    /** 信封请求（默认放行桩件下）；操作者身份经内部令牌承载（范本口径）。 */
    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) {
        return envelopeOf(request, "1", "default", Set.of("super_admin"));
    }

    /** 非超管操作者信封请求（超管保护/跳过分支用例：UserContext roles 不含 super_admin）。 */
    private JsonNode callAsOperator(AbstractMockHttpServletRequestBuilder<?> request) {
        return envelopeOf(request, "3", "default", Set.of("operator"));
    }

    private JsonNode envelopeOf(AbstractMockHttpServletRequestBuilder<?> request,
                                String userId, String tenantId, Set<String> roles) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject(userId, tenantId, Channel.B, roles)))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    /** 原始请求（HTTP 状态断言用）。 */
    private MvcResult rawCall(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("轮询被中断", ex);
            }
        }
        org.assertj.core.api.Assertions.fail("等待条件超时（%s）", timeout);
    }
}
