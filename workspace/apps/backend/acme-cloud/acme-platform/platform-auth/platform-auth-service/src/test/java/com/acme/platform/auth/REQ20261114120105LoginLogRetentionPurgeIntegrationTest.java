package com.acme.platform.auth;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120105 AC-7（auth 侧真实路径）：登录日志保留清理——软删超期物理清理，
 * 未超期/未软删不受影响（platform-auth；Testcontainers PG+Redis，Flyway 真库 V6 建
 * sys_login_log；无 Docker 自动跳过）。
 *
 * <p>口径说明（TL 裁决确认记录④⑦：C 方案——infra 注册任务、Feign 调 auth 内部端点、表访问收敛 auth）：</p>
 * <ul>
 *   <li>真实清理断言在本文件走库：本上下文 Flyway 跑真实迁移（V6 建表含软删列 deleted），
 *       种子三档软删/活跃行后调 auth 内部清理端点（infra 任务的 Feign 落点），断言物理清理效果。</li>
 *   <li>内部端点路径已钉死（TL 裁决④）：POST /internal/v1/login-logs/purge-cleanup，幂等，
 *       返回本次物理清理条数；backend 交付后如实现偏差由 TL 回填（原 AMB-1 关闭）。wire 形状
 *       （data 直接数值 vs {count:n}）未指明，断言双兼容读取。</li>
 *   <li>AMB-2 保留期阈值 backend 配置化且 ≥2 天（裁决 4）：种子取 400 天前 / 1 天前两档软删行，
 *       对任意 ≥2 天阈值鲁棒。「软删」列=deleted（裁决钉死）；「仅插入不改」为业务路径例外条款
 *       （契约⑦），不约束清理任务的物理删。</li>
 *   <li>AMB-3 「软删超期」起算列：登录日志不可变，按 created_at 回拨种子；若实现按独立列起算请对齐。</li>
 *   <li>清理断言经 JdbcTemplate 原生计数（绕开租户/软删过滤面，范本口径）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req120105-purge-secret-0123456789ab",
        "acme.auth.token.hmac-secret=req120105-purge-secret-0123456789ab"
})
class REQ20261114120105LoginLogRetentionPurgeIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** TL 裁决④钉死：auth 内部清理端点（infra 任务 Feign 落点），幂等，返回本次物理清理条数。 */
    private static final String PURGE_ENDPOINT = "/internal/v1/login-logs/purge-cleanup";

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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：种子行全清（真实 V6 表）
        jdbcTemplate.update("delete from sys_login_log where username like 'req120105-%'");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-7：软删超期物理清理，未超期/未软删不受影响（真实 V6 表） ----------

    @Test
    void should_physicallyPurgeExpiredSoftDeletedLoginLogs_when_purgeEndpointTriggered() {
        // 三档种子：超期软删（应物理清理）/ 未超期软删（保留）/ 超期未软删（保留）
        insertLog("req120105-purge-expired", Timestamp.from(Instant.now().minusSeconds(400L * 24 * 3600)), true);
        insertLog("req120105-purge-fresh", Timestamp.from(Instant.now().minusSeconds(24L * 3600)), true);
        insertLog("req120105-purge-active", Timestamp.from(Instant.now().minusSeconds(400L * 24 * 3600)), false);

        JsonNode body = call(post(PURGE_ENDPOINT));

        assertThat(body.path("code").asText()).as("清理端点应受理成功").isEqualTo("0");
        // 返回本次物理清理条数（TL 裁决④）：1 条超期软删；wire 形状（data 数值 vs {count:n}）双兼容读取
        JsonNode data = body.path("data");
        long purged = data.isNumber() ? data.asLong() : data.path("count").asLong(-1);
        assertThat(purged).as("返回的物理清理条数应为 1（仅超期软删行）").isEqualTo(1L);
        assertThat(countOf("req120105-purge-expired"))
                .as("软删超期日志应被物理清理").isZero();
        assertThat(countOf("req120105-purge-fresh"))
                .as("软删未超期日志不受影响（保留期 ≥2 天，AMB-2）").isEqualTo(1L);
        assertThat(countOf("req120105-purge-active"))
                .as("未软删的超期日志不受影响").isEqualTo(1L);
    }

    // ---------- 支撑 ----------

    private void insertLog(String username, Timestamp createdAt, boolean deleted) {
        jdbcTemplate.update("""
                        insert into sys_login_log (username, user_type, ip, user_agent, result, fail_reason,
                                                   trace_id, created_at, deleted)
                        values (?, 'B', '10.0.0.1', 'ua-purge', 'SUCCESS', null, 'trace-purge', ?, ?)
                        """,
                username, createdAt, deleted);
    }

    private long countOf(String username) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_login_log where username = ?", Long.class, username);
        return count == null ? -1L : count;
    }

    private JsonNode call(MockHttpServletRequestBuilder request) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Channel", "B")
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
}
