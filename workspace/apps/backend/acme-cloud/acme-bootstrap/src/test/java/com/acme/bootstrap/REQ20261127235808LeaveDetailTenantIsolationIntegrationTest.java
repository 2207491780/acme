package com.acme.bootstrap;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-235808（B2b P1-05）请假单详情租户隔离集成测试（bootstrap 单体聚合形态）——
 * 覆盖 AC-5：租户 T1 用户凭 id 读取租户 T2 单据 → 查无（租户过滤维持，框架层回归锁定）。
 *
 * <p>装配口径照 {@code REQ20261122233425LeaveE2eIntegrationTest} / {@code MonolithContextSmokeTest}
 * 先例（MonolithApplication + RANDOM_PORT 真容器端口 + 零 Docker 嵌入式 PG/Redis + 种子密码/hmac
 * 环境键）；无事件链腿（stream 维持单体缺省关闭，不引入 Kafka）。</p>
 *
 * <p>双租户数据造法（确认记录⑦零迁移边界）：租户 A=种子 default（U-A=种子超管 admin）；租户 B=
 * 测试夹具直插（platform_system.sys_tenant / sys_user + platform_auth.auth_credential，列集照
 * V1/V2 种子脚本形态，pgcrypto crypt 产 BCrypt 散列；不写任何种子变更，全部夹具行 teardown
 * 清理）。租户隔离由框架层（framework-tenant 过滤）承载，本类按黑盒 HTTP 面断言。</p>
 *
 * <p>断言面（委派单钉死事实 8）：
 * (a) U-A 本人读本人单据 L → 200 视图返回；(b) 租户 B 认证用户 U-B 读 L.id → 与查无同口径
 * （HTTP 400 + 业务失败信封；「请假单不存在」措辞在用例层单测钉死，wire 层为 InvalidRequestException
 * 既有 400 统一映射，契约事实 7「无 wire 变更」）；(c) 无存在性泄露——跨租户命中与真查无
 * （不存在 id）两失败路径响应不可区分（业务码一致、消息数字形态归一后一致）。
 * IT 不承担 AC-3 授权放行（单测已覆盖）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 登录链对无角色用户可用（U-B 不绑任何角色——leave 域无权限注解门，认证即可达用例层）；
 * A-2 InvalidRequestException 经 framework-web 全局映射 HTTP 400（统一失败信封，code≠0）；
 * A-3 夹具直插列集以 V1/V2 种子脚本为准，后续迁移未对 sys_tenant/sys_user/auth_credential
 *     追加无默认值 NOT NULL 列（V24 package_id 为 NULL 缺省已核）。</p>
 */
@SpringBootTest(classes = MonolithApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class REQ20261127235808LeaveDetailTenantIsolationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** B 端登录种子密码（MonolithContextSmokeTest 同款环境键）。 */
    private static final String SEED_SUPERADMIN_PASSWORD = "Monolith@123456";

    private static final String LEAVE_REQUESTS_URL = "/admin/v1/leave/requests";

    /** 租户 B 夹具（固定主键避让种子行；teardown 逐行清理）。 */
    private static final long TENANT_B_ID = 235_808_002L;
    private static final String TENANT_B_CODE = "req235808-tenant-b";
    private static final long USER_B_ID = 235_808_001L;
    private static final long CREDENTIAL_B_ID = 235_808_001L;
    private static final String USER_B_USERNAME = "u235808";
    private static final String USER_B_PASSWORD = "Req235808@Pass1";

    private static final java.nio.file.Path STORAGE_ROOT = tempStorageRoot();

    private static java.nio.file.Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("acme-leave-authz-iso-storage");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("创建存储根目录失败", ex);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        EmbeddedRedisSupport redis = EmbeddedRedisSupport.shared();
        redis.flushAll();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl(
                "acme_monolith_leave_authz_iso",
                "platform_auth,platform_system,platform_infra,platform_scheduler,platform_workflow,business_leave"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("ACME_SEED_SUPERADMIN_PASSWORD", () -> SEED_SUPERADMIN_PASSWORD);
        registry.add("ACME_SEED_DEMO_ADMIN_PASSWORD", () -> "MonolithDemo@123");
        registry.add("ACME_SEED_DEMO_OPERATOR_PASSWORD", () -> "MonolithDemo@456");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
        registry.add("ACME_USER_TOKEN_HMAC_SECRET", () -> "leave-authz-iso-user-token-secret-0123456789abc");
        registry.add("ACME_SECURITY_TOKEN_HMAC_SECRET", () -> "leave-authz-iso-security-token-secret-0123456789");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void seedTenantBFixture() {
        // 先清残留（固定主键 + 编码键），再插夹具行（幂等可重放）
        cleanTenantBFixture();
        jdbcTemplate.update("""
                insert into platform_system.sys_tenant (id, tenant_code, tenant_name, status, remark,
                                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, '租户B（越权收口IT）', 'ENABLED', 'tester-it',
                        now(), 'tester-it', now(), 'tester-it', 0, false)
                """, TENANT_B_ID, TENANT_B_CODE);
        jdbcTemplate.update("""
                insert into platform_system.sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, '租户B用户', null, null, 'ENABLED', false,
                        now(), 'tester-it', now(), 'tester-it', 0, false)
                """, USER_B_ID, TENANT_B_CODE, USER_B_USERNAME);
        // pgcrypto crypt 与种子同源（auth V2 迁移已在本库建扩展）；BCrypt 散列与登录校验端兼容
        jdbcTemplate.update("""
                insert into platform_auth.auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, public.crypt(?, public.gen_salt('bf', 10)), now(),
                        now(), 'tester-it', now(), 'tester-it', 0, false)
                """, CREDENTIAL_B_ID, USER_B_ID, TENANT_B_CODE, USER_B_PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据：leave_request 行集（本类 businessKey 前缀）+ 租户 B 夹具行 + U-B 会话。
        // wf_* 表不清（实例行按 businessKey/实例 id 天然隔离，类级库随类销毁）。
        try {
            jdbcTemplate.update("delete from " + qualified("leave_request") + " where business_key like 'leave-authz-%'");
        } catch (org.springframework.dao.DataAccessException ignored) {
            // 表未随实现落地（等实现期）：跳过清理，测试自身会给出明确信号
        }
        cleanTenantBFixture();
    }

    private void cleanTenantBFixture() {
        jdbcTemplate.update("delete from platform_auth.auth_session where user_id = ?", USER_B_ID);
        jdbcTemplate.update("delete from platform_auth.auth_credential where user_id = ?", USER_B_ID);
        jdbcTemplate.update("delete from platform_system.sys_user where id = ?", USER_B_ID);
        jdbcTemplate.update("delete from platform_system.sys_tenant where id = ?", TENANT_B_ID);
    }

    // ---------- AC-5(a)：租户 A 用户 U-A 本人读本人单据 → 200 视图 ----------

    @Test
    void should_returnLeaveView_when_ownerReadsOwnRequest() throws Exception {
        String adminToken = loginAdmin();
        long leaveId = createLeave(adminToken, "leave-authz-owner-" + SEQ.incrementAndGet());

        HttpResponse<String> response = get(LEAVE_REQUESTS_URL + "/" + leaveId, adminToken);

        assertThat(response.statusCode())
                .as("owner 直读应 200（AC-5 正向腿 / 委派单事实 8a）：%s", response.body())
                .isEqualTo(200);
        JsonNode body = bodyOf(response);
        ok(body, "owner 直读返回视图信封");
        assertThat(idOf(body)).as("返回视图应为本单据").isEqualTo(leaveId);
    }

    // ---------- AC-5(b)：租户 B 认证用户 U-B 读租户 A 单据 → 与查无同口径（400） ----------

    @Test
    void should_returnNotFoundSemantics_when_otherTenantUserReadsForeignRequest() throws Exception {
        String adminToken = loginAdmin();
        long leaveId = createLeave(adminToken, "leave-authz-cross-" + SEQ.incrementAndGet());
        String tenantBToken = loginTenantBUser();

        HttpResponse<String> response = get(LEAVE_REQUESTS_URL + "/" + leaveId, tenantBToken);

        assertThat(response.statusCode())
                .as("跨租户读应与查无同口径 HTTP 400（AC-5，契约 API 节钉死映射），实际=%s %s",
                        response.statusCode(), response.body())
                .isEqualTo(400);
        JsonNode body = bodyOf(response);
        assertThat(body.path("success").asBoolean()).as("业务失败信封").isFalse();
        assertThat(body.path("code").asText()).as("业务失败信封 code≠0").isNotEqualTo("0");
        // 「请假单不存在」措辞由用例层单测钉死（REQ20261127235808LeaveDetailAuthzTest，AC-2）；
        // wire 层 message 为 InvalidRequestException 既有 400 统一映射（契约事实 7：无 wire 变更）
    }

    // ---------- AC-5(c)：无存在性泄露——跨租户命中与真查无响应不可区分 ----------

    @Test
    void should_beIndistinguishableBetweenCrossTenantReadAndMissingRecord() throws Exception {
        String adminToken = loginAdmin();
        long leaveId = createLeave(adminToken, "leave-authz-indist-" + SEQ.incrementAndGet());
        String tenantBToken = loginTenantBUser();
        // 真查无基线 id：leave id 为雪花大值，+1 无同表行
        long missingId = leaveId + 1;

        HttpResponse<String> crossTenant = get(LEAVE_REQUESTS_URL + "/" + leaveId, tenantBToken);
        HttpResponse<String> missing = get(LEAVE_REQUESTS_URL + "/" + missingId, tenantBToken);

        assertThat(crossTenant.statusCode())
                .as("跨租户命中行不可见 → 400（AC-5）：%s", crossTenant.body()).isEqualTo(400);
        assertThat(missing.statusCode())
                .as("真查无基线 → 400：%s", missing.body()).isEqualTo(400);
        JsonNode crossBody = bodyOf(crossTenant);
        JsonNode missingBody = bodyOf(missing);
        assertThat(crossBody.path("code").asText())
                .as("两失败路径业务码一致（不泄露存在性）").isEqualTo(missingBody.path("code").asText());
        assertThat(normalizeDigits(crossBody.path("message").asText()))
                .as("两失败路径消息口径一致（id 等数字形态细节归一后不可区分）")
                .isEqualTo(normalizeDigits(missingBody.path("message").asText()));
    }

    // ---------- HTTP 支撑（E2E 范本口径：真容器端口全过滤链） ----------

    private String loginAdmin() throws Exception {
        HttpResponse<String> login = postJson(null, "/admin/v1/auth/tokens", """
                {"tenantCode":"default","username":"admin","password":"%s"}
                """.formatted(SEED_SUPERADMIN_PASSWORD));
        assertThat(login.statusCode()).as("登录应 200：%s", login.body()).isEqualTo(200);
        String body = login.body();
        assertThat(body).contains("\"success\":true");
        String token = extractJsonStringField(body, "accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 租户 B 认证用户 U-B 登录（夹具直插租户/用户/凭据，A-1 表面假定）。 */
    private String loginTenantBUser() throws Exception {
        HttpResponse<String> login = postJson(null, "/admin/v1/auth/tokens", """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(TENANT_B_CODE, USER_B_USERNAME, USER_B_PASSWORD));
        assertThat(login.statusCode()).as("租户 B 用户登录应 200（A-1）：%s", login.body()).isEqualTo(200);
        String body = login.body();
        assertThat(body).contains("\"success\":true");
        String token = extractJsonStringField(body, "accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private long createLeave(String token, String businessKey) throws Exception {
        JsonNode created = bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tenant-isolation-it",
                 "startTime":"2026-12-01T09:00:00","endTime":"2026-12-02T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
        ok(created, "请假申请应成功");
        long leaveId = idOf(created);
        assertThat(leaveId).as("申请应落库返回 id").isPositive();
        return leaveId;
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String bearerToken, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode bodyOf(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** data.id 宽容解析（data.id / data 裸值；Long wire=字符串 FR-024）。 */
    private long idOf(JsonNode resp) {
        JsonNode data = resp.path("data");
        JsonNode id = data.has("id") ? data.path("id") : data;
        return id.asLong();
    }

    private static String extractJsonStringField(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("响应应包含字段 %s：%s", field, json).isTrue();
        return matcher.group(1);
    }

    /** 数字形态细节归一（单据 id 等差异抹平，仅比对响应口径形态）。 */
    private static String normalizeDigits(String message) {
        return message == null ? "" : message.replaceAll("\\d+", "#");
    }

    // ---------- DB 夹具读取（leave_request 表 schema 动态定位，E2E 范本口径） ----------

    private String qualified(String tableName) {
        List<String> schemas = jdbcTemplate.queryForList(
                "select table_schema from information_schema.tables "
                        + "where table_name = ? and table_schema not in ('pg_catalog','information_schema')",
                String.class, tableName);
        return schemas.isEmpty() ? tableName : schemas.get(0) + "." + tableName;
    }
}
