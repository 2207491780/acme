package com.acme.platform.infra;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120105 AC-7（infra 侧）：login-log-retention-cleanup 任务注册 + 手动触发受理
 * （platform-infra；Testcontainers PG+Redis，照抄 {@code REQ20261114071607JobScheduleIntegrationTest}
 * 装配口径；无 Docker 自动跳过）。
 *
 * <p>口径说明（TL 裁决确认记录④⑦：C 方案——infra 注册任务、Feign 调 auth 内部端点、表访问收敛 auth）：</p>
 * <ul>
 *   <li>真实清理断言落 platform-auth 侧 {@code REQ20261114120105LoginLogRetentionPurgeIntegrationTest}
 *       （真实 V6 表 + auth 内部端点走库）；本文件不建表、不触表（不在 infra schema 自建表——裁决 4）。</li>
 *   <li>触发链路已钉死（TL 回填，确认记录⑧）：异步执行（TaskTrigger，不同步传播）；Feign 失败时
 *       执行记录仍回填 FAILED+finished_at——本文件 finished_at 轮询断言满足，无需桩件；
 *       清理行为正确性由 auth 侧文件兜底覆盖。</li>
 *   <li>注册表 key 字段名（jobKey/key）与执行表列名（finished_at）按既有 job 域先例（AMB-1）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req120105-cleanup-secret-012345678"
})
class REQ20261114120105LoginLogCleanupIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String JOB_KEY = "login-log-retention-cleanup";
    private static final Duration TRIGGER_SETTLE = Duration.ofSeconds(10);

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

    @MockitoBean
    private com.acme.platform.infra.infrastructure.client.AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：执行记录 + ShedLock 锁行（本文件不触 sys_login_log）
        jdbcTemplate.update("delete from sys_job_execution");
        jdbcTemplate.update("delete from shedlock_locks");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-7 前置：任务注册表含 login-log-retention-cleanup ----------

    @Test
    void should_listLoginLogRetentionCleanup_when_queryJobRegistry() {
        JsonNode body = call(get("/admin/v1/infra/jobs"));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.isArray()).isTrue();
        boolean registered = false;
        for (JsonNode job : data) {
            if (JOB_KEY.equals(job.path("jobKey").asText(null))
                    || JOB_KEY.equals(job.path("key").asText(null))) {
                registered = true;
            }
        }
        assertThat(registered).as("任务注册表应含 " + JOB_KEY + "（key 字段名 AMB-1）").isTrue();
    }

    // ---------- AC-7：手动触发受理（执行记录落库；清理行为断言在 auth 侧文件，见类注释） ----------

    @Test
    void should_acceptTriggerAndRecordExecution_when_loginLogRetentionCleanupRun() {
        JsonNode body = triggerRun();

        assertThat(body.path("code").asText()).as("手动触发应受理成功").isEqualTo("0");
        awaitTrue(TRIGGER_SETTLE, () -> {
            Long finished = jdbcTemplate.queryForObject(
                    "select count(*) from sys_job_execution where finished_at is not null", Long.class);
            return finished != null && finished > 0;
        });
    }

    // ---------- 支撑 ----------

    private JsonNode triggerRun() {
        return call(post("/admin/v1/infra/jobs/{jobKey}/run", JOB_KEY));
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
