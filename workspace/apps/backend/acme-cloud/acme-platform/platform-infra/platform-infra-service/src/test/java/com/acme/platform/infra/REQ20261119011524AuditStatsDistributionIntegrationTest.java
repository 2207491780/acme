package com.acme.platform.infra;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261119-011524 审计动作分布统计内部接口（platform-infra，
 * GET /internal/v1/audit-stats/distribution）服务域集成测试（Testcontainers PG，
 * 装配口径照抄 {@code InfraIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖 AC-1（infra 半边，服务身份调用免权限点）：acme_op_audit action 分布聚合——
 * 按条数降序、top N 截断、窗口过滤（occurred_at）、跨租户平台口径（多租户行并入一桶，
 * @IgnoreTenant 显式旁路声明 12.4.2）、deleted=false 默认面对齐（软删行不计）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 事件以 jdbc 直插预置（列集对齐 V1__init_schema.sql acme_op_audit；
 *       AuditIngestUseCase 直灌会经 TenantContext 归属单租户，跨租户并入断言需双租户直插）。</li>
 *   <li>AMB-2 调用方身份模拟 platform-system 服务主体（Q-D08：内部令牌 userId=服务名）；
 *       internal 端点无权限点，roles 仅随令牌装配。</li>
 *   <li>AMB-3 days/top 入参窄化防御（days&lt;1 / top&lt;1 归 1）属实现细节，契约未钉死，
 *       用例以 days=1 窗口过滤断言为准，不做非法入参断言（保持最小契约面）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req011524-auditstats-it-secret-01234567"
})
class REQ20261119011524AuditStatsDistributionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANT_A = "req011524-tenant-a";
    private static final String TENANT_B = "req011524-tenant-b";

    private static final long EVENT_ID_BASE = 911524_000L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** 本地盘存储根（REQ-20261114-071607 起服务装配含 framework-storage；type=local 缺省，root-dir 必配）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req011524-auditstats-it-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
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
        jdbcTemplate.update("delete from acme_op_audit where event_id like 'req011524-%'");
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown（本类独立容器，残留随容器销毁兜底）
        jdbcTemplate.update("delete from acme_op_audit where event_id like 'req011524-%'");
    }

    // ---------- AC-1：action 分布聚合（降序 / top 截断 / 跨租户并入 / 窗口过滤 / 软删不计） ----------

    @Test
    void should_aggregateActionDistributionAcrossTenants_when_distributionCalled() {
        // system.user.*：跨两租户共 4 条（3+1）；infra.file.upload：2 条；req011524.sparse：1 条（窗口外）
        seedEvent("req011524-e1", TENANT_A, "system.user.create", "now()");
        seedEvent("req011524-e2", TENANT_A, "system.user.create", "now()");
        seedEvent("req011524-e3", TENANT_B, "system.user.create", "now()");
        seedEvent("req011524-e4", TENANT_B, "system.user.create", "now()");
        seedEvent("req011524-e5", TENANT_A, "infra.file.upload", "now()");
        seedEvent("req011524-e6", TENANT_B, "infra.file.upload", "now() - interval '1 day'");
        seedEvent("req011524-e7", TENANT_A, "req011524.sparse", "now() - interval '40 day'");
        seedEvent("req011524-e8", TENANT_A, "system.user.create", "now()", true);

        JsonNode body = callInternal("/internal/v1/audit-stats/distribution?days=30&top=5");

        assertThat(body.path("code").asText()).as("internal 统计端点恒 200 信封").isEqualTo("0");
        List<Map<String, String>> items = readItems(body.path("data"));
        assertThat(items).as("窗口内两动作（窗口外与软删行不计）").hasSize(2);
        assertThat(items.get(0)).as("按条数降序：跨租户并入一桶 system.user.create=4")
                .containsEntry("action", "system.user.create").containsEntry("count", "4");
        assertThat(items.get(1)).as("跨租户并入一桶 infra.file.upload=2")
                .containsEntry("action", "infra.file.upload").containsEntry("count", "2");
    }

    @Test
    void should_truncateToTopN_when_topNarrowed() {
        seedEvent("req011524-e1", TENANT_A, "action.a", "now()");
        seedEvent("req011524-e2", TENANT_A, "action.a", "now()");
        seedEvent("req011524-e3", TENANT_A, "action.b", "now()");
        seedEvent("req011524-e4", TENANT_A, "action.c", "now()");

        JsonNode body = callInternal("/internal/v1/audit-stats/distribution?days=30&top=2");

        List<Map<String, String>> items = readItems(body.path("data"));
        assertThat(items).as("top=2 截断").hasSize(2);
        assertThat(items.get(0)).as("并列计数 1 按动作稳定并列序")
                .containsEntry("count", "2");
        assertThat(items.get(1).get("count")).isEqualTo("1");
    }

    @Test
    void should_returnEmptyList_when_noEventInWindow() {
        JsonNode body = callInternal("/internal/v1/audit-stats/distribution?days=30&top=5");

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").isArray()).isTrue();
        assertThat(body.path("data").size()).as("窗口内无事件 → 空列表").isZero();
    }

    // ---------- 支撑 ----------

    /** 直插审计事件（AMB-1：列集对齐 V1 acme_op_audit；occurred_at 表达式 + 软删标记可选）。 */
    private void seedEvent(String eventId, String tenantId, String action, String occurredAt) {
        seedEvent(eventId, tenantId, action, occurredAt, false);
    }

    private void seedEvent(String eventId, String tenantId, String action,
                           String occurredAt, boolean deleted) {
        jdbcTemplate.update("""
                insert into acme_op_audit (id, event_id, trace_id, tenant_id, operator_id, channel,
                                           action, target_type, target_id, content, occurred_at,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 'req011524-trace', ?, '911524', 'B',
                        ?, 'req011524', '42', '{}', %s,
                        now(), 'req011524-it', now(), 'req011524-it', 0, ?)
                """.formatted(occurredAt),
                EVENT_ID_SEQUENCE.incrementAndGet(), eventId, tenantId, action, deleted);
    }

    /** 测试内自增 id（雪花主键无序列，直插手工取号）。 */
    private static final java.util.concurrent.atomic.AtomicLong EVENT_ID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(EVENT_ID_BASE);

    /** internal 调用（platform-system 服务主体，Q-D08，AMB-2）；业务语义恒 HTTP 200 信封。 */
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

    /** 统计项列表读取（data 数组 → [{action,count}] 有序 Map 列表）。 */
    private static List<Map<String, String>> readItems(JsonNode data) {
        List<Map<String, String>> items = new ArrayList<>();
        for (JsonNode item : data) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("action", item.path("action").asText());
            row.put("count", item.path("count").asText());
            items.add(row);
        }
        return items;
    }
}
