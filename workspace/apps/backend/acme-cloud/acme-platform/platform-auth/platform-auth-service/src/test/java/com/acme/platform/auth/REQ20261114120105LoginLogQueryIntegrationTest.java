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
import com.acme.platform.system.api.SystemErrorCodes;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261114-120105 AC-2 / AC-6（login-logs 侧）：登录日志分页筛选（platform-auth）服务域集成测试
 * （Testcontainers PG+Redis，照抄 {@code REQ20261114120104DeptApiIntegrationTest} 装配口径；
 * 无 Docker 自动跳过）。
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-3 筛选/分页参数名：TL 裁决确认记录⑦钉死 page/size/username/userType/result/from/to
 *       （from/to=ISO，AuditPage 先例）。</li>
 *   <li>result wire 字面量（backend 交付钉死）：SUCCESS / FAILURE（精确匹配筛选）；user_type=B/C。</li>
 *   <li>AMB-7 响应形状推定 data.records + data.total（CrudTable 分页口径）。</li>
 *   <li>AMB-8 权限判定经 AuthorizationGateway（platform-auth 无本地 sys_permission，审计域同口径）：
 *       桩件 @BeforeEach 全放行，AC-6 用例内改桩全拒绝。</li>
 *   <li>sys_login_log 列集 = 契约变更清单（username/user_type/ip/user_agent/result/fail_reason/
 *       trace_id/created_at）；若实现含额外 NOT NULL 列（如软删列 deleted 有默认值不阻断），插入失败时
 *       由 TL 对齐种子 SQL。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req120105-logquery-secret-012345678",
        "acme.auth.token.hmac-secret=req120105-logquery-secret-012345678"
})
class REQ20261114120105LoginLogQueryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 时间锚（相对 now 回拨，避免绝对时间脆弱）：alice-B=now / alice-C=now-1h / bob=now-2h / carol=now-40d。 */
    private static final Instant T_ALICE_B = Instant.now();
    private static final Instant T_ALICE_C = Instant.now().minusSeconds(3600);
    private static final Instant T_BOB = Instant.now().minusSeconds(2 * 3600);
    private static final Instant T_CAROL = Instant.now().minusSeconds(40L * 24 * 3600);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

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

    /** 权限判定桩件（AMB-8）：默认全放行；AC-6 用例内改桩全拒绝。 */
    @MockitoBean
    private com.acme.platform.auth.infrastructure.client.AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        allowAllPermissions();
        seedLoginLogs();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_login_log");
        TenantContext.clear();
        UserContext.clear();
    }

    private void allowAllPermissions() {
        when(authorizationGateway.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    private void denyAllPermissions() {
        when(authorizationGateway.hasPermission(anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new BizException(new PermissionDenied()))
                .when(authorizationGateway).requirePermission(anyString());
    }

    private void seedLoginLogs() {
        insertLog("alice", "B", "10.0.0.1", "ua-alice", "SUCCESS", null, "trace-alice-b", T_ALICE_B);
        insertLog("alice", "C", "10.0.0.2", "ua-alice-c", "SUCCESS", null, "trace-alice-c", T_ALICE_C);
        insertLog("bob", "B", "10.0.0.3", "ua-bob", "FAILURE", "密码错误", "trace-bob", T_BOB);
        insertLog("carol", "B", "10.0.0.4", "ua-carol", "SUCCESS", null, "trace-carol", T_CAROL);
    }

    // 雪花 id 由应用指派、DB 无缺省：种子行须显式给主键（隔离测试库内唯一即可）
    private static long seedIdSeq = 900_000L;

    private void insertLog(String username, String userType, String ip, String ua, String result,
                           String failReason, String traceId, Instant createdAt) {
        jdbcTemplate.update("""
                        insert into sys_login_log (id, username, user_type, ip, user_agent, result, fail_reason,
                                                   trace_id, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ++seedIdSeq, username, userType, ip, ua, result, failReason, traceId, Timestamp.from(createdAt));
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

    // ---------- AC-2：无筛选分页（page/size 生效，total 正确） ----------

    @Test
    void should_pageLoginLogs_when_queryWithoutFilter() {
        JsonNode body = call(get("/admin/v1/auth/login-logs")
                .queryParam("page", "1").queryParam("size", "2"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode records = recordsOf(body);
        assertThat(records.isArray()).as("分页结构 data.records（AMB-7）").isTrue();
        assertThat(records.size()).as("size=2 时单页条数 ≤2").isLessThanOrEqualTo(2);
        assertThat(body.path("data").path("total").asLong(-1))
                .as("无筛选 total=全部 4 条（wire 形状 AMB-7）").isEqualTo(4);
    }

    // ---------- AC-2：username 筛选 ----------

    @Test
    void should_filterLoginLogsByUsername_when_usernameGiven() {
        JsonNode body = call(get("/admin/v1/auth/login-logs")
                .queryParam("page", "1").queryParam("size", "10").queryParam("username", "alice"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(usernamesOf(recordsOf(body)))
                .as("username=alice 应只含 alice 的两条（B/C 双端）")
                .containsExactly("alice", "alice");
    }

    // ---------- AC-2：userType / result 筛选 ----------

    @Test
    void should_filterLoginLogsByUserTypeAndResult_when_given() {
        JsonNode byType = call(get("/admin/v1/auth/login-logs")
                .queryParam("page", "1").queryParam("size", "10").queryParam("userType", "C"));
        assertThat(byType.path("code").asText()).isEqualTo("0");
        assertThat(usernamesOf(recordsOf(byType)))
                .as("userType=C 应只含 alice 的 C 端日志").containsExactly("alice");
        assertThat(recordsOf(byType).get(0).path("userType").asText()).isEqualTo("C");

        JsonNode byResult = call(get("/admin/v1/auth/login-logs")
                .queryParam("page", "1").queryParam("size", "10").queryParam("result", "FAILURE"));
        assertThat(byResult.path("code").asText()).isEqualTo("0");
        assertThat(usernamesOf(recordsOf(byResult)))
                .as("result=FAILURE 应只含 bob 的失败日志（字面量已钉死）").containsExactly("bob");
    }

    // ---------- AC-2：时间区间筛选（含边界，不含区间外） ----------

    @Test
    void should_filterLoginLogsByTimeRange_when_startAndEndGiven() {
        JsonNode body = call(get("/admin/v1/auth/login-logs")
                .queryParam("page", "1").queryParam("size", "10")
                .queryParam("from", T_BOB.plusSeconds(60).toString())
                .queryParam("to", T_ALICE_B.plusSeconds(60).toString()));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(usernamesOf(recordsOf(body)))
                .as("区间 [bob+60s, alice-B] 应含 alice 双端（C 端 1h 前 / B 端 now），不含 bob/carol")
                .containsExactlyInAnyOrder("alice", "alice");
    }

    // ---------- AC-6：无权限点 → 统一拒绝（Result.code≠0，勿断言 4xx） ----------

    @Test
    void should_rejectLoginLogQuery_when_noPermission() {
        denyAllPermissions();

        JsonNode body = call(get("/admin/v1/auth/login-logs"));

        assertThat(body.path("success").asBoolean())
                .as("缺少 auth:loginlog:list 权限时应统一拒绝").isFalse();
        assertThat(body.path("code").asText())
                .as("权限拒绝精确码（确认记录⑧，HTTP 403 + Result 信封 code≠0，勿断言 4xx）")
                .isEqualTo("platform-system-07-0001");
    }

    // ---------- 支撑 ----------

    private JsonNode call(MockHttpServletRequestBuilder request) {
        return call((AbstractMockHttpServletRequestBuilder<?>) request);
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    /** 分页数据定位：data 数组 / {records} 双兼容（AMB-7）。 */
    private static JsonNode recordsOf(JsonNode body) {
        JsonNode data = body.path("data");
        return data.isArray() ? data : data.path("records");
    }

    private static List<String> usernamesOf(JsonNode records) {
        return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(item -> item.path("username").asText())
                .toList();
    }
}
