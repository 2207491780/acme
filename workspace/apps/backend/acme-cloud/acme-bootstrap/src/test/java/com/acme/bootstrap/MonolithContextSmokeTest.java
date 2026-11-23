package com.acme.bootstrap;

import com.acme.bootstrap.audit.LocalAuditEventSink;
import com.acme.bootstrap.local.LocalAuditStatsClient;
import com.acme.bootstrap.local.LocalAuthProfileClient;
import com.acme.bootstrap.local.LocalAuthorizationClient;
import com.acme.bootstrap.local.LocalDataScopeClient;
import com.acme.bootstrap.local.LocalLoginLogCleanupClient;
import com.acme.bootstrap.local.LocalLoginLogStatsClient;
import com.acme.bootstrap.local.LocalOnlineCountClient;
import com.acme.bootstrap.local.LocalSystemConfigClient;
import com.acme.bootstrap.local.LocalTenantClient;
import com.acme.bootstrap.local.LocalTenantQuotaClient;
import com.acme.bootstrap.local.LocalTenantSessionRevokeClient;
import com.acme.bootstrap.local.LocalWorkflowRuntimeClient;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventSink;
import com.acme.platform.auth.api.LoginLogStatsClient;
import com.acme.platform.auth.api.OnlineCountClient;
import com.acme.platform.auth.api.TenantSessionRevokeClient;
import com.acme.platform.infra.api.AuditStatsClient;
import com.acme.platform.infra.internal.client.LoginLogCleanupClient;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.DataScopeClient;
import com.acme.platform.system.api.SystemConfigClient;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantQuotaClient;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单体上下文冒烟测试（REQ-20261118-002130 AC-2/3/4/5：Testcontainers PG + Redis，
 * 无 Docker 环境自动跳过——跳过不等于验证，合并前必须在有 Docker 环境实跑）：
 * <ul>
 *   <li>AC-2：三 Flyway 实例各自迁移落位（platform_auth/platform_system/platform_infra 三 schema
 *       的表与 flyway_schema_history 各自独立）；上下文无缺失 bean（含 @MapperScan 三服务 mapper 面）；</li>
 *   <li>AC-4：12 个本地适配器 bean 存在且 @Primary（按类型解析全部命中本地短路实现；
 *       Feign 工厂 bean 定义惰性（lazy-attributes-resolution 实地验证，永不实例化）；</li>
 *   <li>AC-3：B 端登录 200（公开端点经 BootstrapAuthFilter 豁免）→ /me 携令牌 200
 *       （用户令牌验签→撤销检查→内部令牌注入→InternalTokenAuthFilter 上下文绑定全链）、
 *       无令牌 401；/actuator/health UP；HTTP 走真容器端口（java.net.http.HttpClient，全过滤链真实装配）；</li>
 *   <li>AC-5：本地审计 sink @Primary 直落 acme_op_audit（无 Kafka 出站）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MonolithApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonolithContextSmokeTest {

    /** B 端登录种子密码（V2 超管凭据 placeholder 测试取值，非生产；requiredSeed 经环境键读取）。 */
    private static final String SEED_SUPERADMIN_PASSWORD = "Monolith@123456";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final java.nio.file.Path STORAGE_ROOT = tempStorageRoot();

    private static java.nio.file.Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("acme-monolith-smoke-storage");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("创建存储根目录失败", ex);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        // 四 schema 合并 search_path（MonolithDataSourceConfig 语义同款；platform_scheduler 为
        // REQ-20261121-071002 确认⑥聚合增补——scheduler tick/锁表在单体随 Spring 启动）；
        // env 键与分布式一致。追加符：Testcontainers 2.x 的 jdbcUrl 已带 query（loggerLevel=OFF），须用 & 续参
        registry.add("spring.datasource.url", () -> {
            String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
            return POSTGRES.getJdbcUrl() + separator
                    + "currentSchema=platform_auth,platform_system,platform_infra,platform_scheduler,"
                    + "platform_workflow,business_leave";
        });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        // 种子密码三键（MonolithFlywayConfig requiredSeed 环境键，DynamicPropertySource 内联属性可达）
        registry.add("ACME_SEED_SUPERADMIN_PASSWORD", () -> SEED_SUPERADMIN_PASSWORD);
        registry.add("ACME_SEED_DEMO_ADMIN_PASSWORD", () -> "MonolithDemo@123");
        registry.add("ACME_SEED_DEMO_OPERATOR_PASSWORD", () -> "MonolithDemo@456");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
        // 双 hmac 密钥域环境键（application-monolith.yml 的 ${ACME_*} 占位符在本测试上下文经
        // 内联属性解析——config.import 导入面优先级高于声明文件，test yml 字面量会被其覆盖）
        registry.add("ACME_USER_TOKEN_HMAC_SECRET", () -> "monolith-it-user-token-secret-0123456789abcdef");
        registry.add("ACME_SECURITY_TOKEN_HMAC_SECRET", () -> "monolith-it-security-token-secret-0123456789");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditEventSink auditEventSink;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ---------- AC-4：本地适配器 @Primary 与 Feign 惰性 ----------

    @Test
    void localAdaptersArePrimaryResolutions() {
        assertThat(beanFactory.getBean(AuthProfileClient.class))
                .isInstanceOf(LocalAuthProfileClient.class);
        assertThat(beanFactory.getBean(AuthorizationClient.class))
                .isInstanceOf(LocalAuthorizationClient.class);
        assertThat(beanFactory.getBean(TenantClient.class)).isInstanceOf(LocalTenantClient.class);
        assertThat(beanFactory.getBean(SystemConfigClient.class))
                .isInstanceOf(LocalSystemConfigClient.class);
        assertThat(beanFactory.getBean(DataScopeClient.class)).isInstanceOf(LocalDataScopeClient.class);
        assertThat(beanFactory.getBean(TenantSessionRevokeClient.class))
                .isInstanceOf(LocalTenantSessionRevokeClient.class);
        assertThat(beanFactory.getBean(LoginLogCleanupClient.class))
                .isInstanceOf(LocalLoginLogCleanupClient.class);
        assertThat(beanFactory.getBean(OnlineCountClient.class))
                .isInstanceOf(LocalOnlineCountClient.class);
        assertThat(beanFactory.getBean(LoginLogStatsClient.class))
                .isInstanceOf(LocalLoginLogStatsClient.class);
        assertThat(beanFactory.getBean(AuditStatsClient.class))
                .isInstanceOf(LocalAuditStatsClient.class);
        assertThat(beanFactory.getBean(TenantQuotaClient.class))
                .isInstanceOf(LocalTenantQuotaClient.class);
        // REQ-20261122-233425 确认⑥：workflow 运行时契约本地短路（12 号适配器）
        assertThat(beanFactory.getBean(WorkflowRuntimeClient.class))
                .isInstanceOf(LocalWorkflowRuntimeClient.class);
    }

    @Test
    void feignFactoryBeansAreLazyAndNeverInstantiated() {
        // 确认②实地验证：spring.cloud.openfeign.lazy-attributes-resolution=true 下 Feign 工厂 bean
        // 定义惰性注册（Registrar 以 feignClientsRegistrarFactoryBean 属性标记，instance supplier
        // 构建、永不实例化即不依赖服务发现）；@Primary 本地适配器承担全部跨域注入点
        int feignDefinitions = 0;
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            if (definition.hasAttribute("feignClientsRegistrarFactoryBean")) {
                feignDefinitions++;
                assertThat(definition.isLazyInit())
                        .as("Feign 工厂 bean 应惰性注册：%s", name).isTrue();
                assertThat(definition.isPrimary())
                        .as("Feign 工厂 bean 应被降级为非 primary（本地适配器胜出）：%s", name).isFalse();
            }
        }
        assertThat(feignDefinitions).as("五服务去重后共 14 个显式 clients 声明"
                + "（scheduler/infra/workflow 复用 AuthorizationClient 同配置声明不新增；"
                + "workflow 增补 SchedulerJobClient/WorkflowIdentityClient 两新契约；"
                + "infra 增补 TenantQuotaClient 配额契约 REQ-20261122-150802；"
                + "bootstrap 聚合配置增补 WorkflowRuntimeClient 运行时契约 REQ-20261122-233425 确认⑥"
                + "——business-leave 无独立 @EnableFeignClients 数组，SDK client 登记在聚合配置）")
                .isEqualTo(14);
    }

    // ---------- AC-2：三 Flyway 实例各自迁移落位 ----------

    @Test
    void threeFlywayInstancesMigratedPerSchema() {
        // 三 schema 各自的迁移历史（同库同 DataSource，defaultSchema 独立 → 历史表各自 schema 落位）
        assertThat(tableExists("platform_auth", "flyway_schema_history")).isTrue();
        assertThat(tableExists("platform_system", "flyway_schema_history")).isTrue();
        assertThat(tableExists("platform_infra", "flyway_schema_history")).isTrue();
        // 业务表落位抽核（各域代表表：凭据 / 用户 / 审计与锁表 / workflow 引擎表 / leave 请假单）
        assertThat(tableExists("platform_auth", "auth_credential")).isTrue();
        assertThat(tableExists("platform_auth", "sys_login_log")).isTrue();
        assertThat(tableExists("platform_system", "sys_user")).isTrue();
        assertThat(tableExists("platform_system", "sys_config")).isTrue();
        assertThat(tableExists("platform_infra", "acme_op_audit")).isTrue();
        assertThat(tableExists("platform_infra", "shedlock_locks")).isTrue();
        // REQ-20261122-233425 确认⑤：leave 业务域 V1 落位（business_leave schema 独占）
        assertThat(tableExists("business_leave", "leave_request")).isTrue();
        assertThat(tableExists("business_leave", "flyway_schema_history")).isTrue();
        // 三 schema 无串位：auth 域迁移不在 system/infra 落表（search_path 合流污染防回归）
        assertThat(tableExists("platform_auth", "sys_user")).isFalse();
        assertThat(tableExists("platform_system", "auth_credential")).isFalse();
        // 种子落位（V2 超管凭据 + system V2 基线租户）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from platform_auth.auth_credential", Long.class)).isGreaterThanOrEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from platform_system.sys_tenant where tenant_code='default'",
                Long.class)).isEqualTo(1L);
    }

    private boolean tableExists(String schema, String table) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema=? and table_name=?",
                Long.class, schema, table);
        return count != null && count > 0;
    }

    // ---------- AC-3：健康探针与边缘认证链路 ----------

    @Test
    void actuatorHealthIsUp() throws Exception {
        HttpResponse<String> health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
    }

    @Test
    void adminLoginMeAndUnauthorizedFlow() throws Exception {
        // 无令牌访问受保护端点 → 401（BootstrapAuthFilter 验签缺失拒绝）
        HttpResponse<String> unauthenticated = get("/admin/v1/system/me", null);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        // B 端登录（公开端点豁免）→ 签发成功；登录链跨域查询全部经本地适配器直调（无 HTTP 出站）
        HttpResponse<String> login = postJson("/admin/v1/auth/tokens", """
                {"tenantCode":"default","username":"admin","password":"%s"}
                """.formatted(SEED_SUPERADMIN_PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        String body = login.body();
        assertThat(body).contains("\"success\":true");
        String token = extractJsonStringField(body, "accessToken");
        assertThat(token).isNotBlank();

        // 携令牌 /me → 200（验签 + 撤销检查 + 内部令牌注入 + InternalTokenAuthFilter 绑定全链）
        HttpResponse<String> me = get("/admin/v1/system/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("admin");
    }

    /** 极简 JSON 字符串字段提取（响应形态固定，避免为冒烟引入反序列化栈）。 */
    private static String extractJsonStringField(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("响应应包含字段 %s：%s", field, json).isTrue();
        return matcher.group(1);
    }

    // ---------- AC-5：本地审计 sink 直落 ----------

    @Test
    void localAuditSinkWritesOpAuditWithoutMq() {
        assertThat(auditEventSink).isInstanceOf(LocalAuditEventSink.class);
        String eventId = UUID.randomUUID().toString();
        AuditEvent event = new AuditEvent(eventId, "trace-smoke", "span-smoke", "default", "1", "B",
                "smoke.audit.ingest", "smoke", "42", "{}", Instant.now(), "bootstrap");
        auditEventSink.onEvent(event);
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from platform_infra.acme_op_audit where event_id=?", Long.class, eventId);
        assertThat(rows).isEqualTo(1L);
    }
}
