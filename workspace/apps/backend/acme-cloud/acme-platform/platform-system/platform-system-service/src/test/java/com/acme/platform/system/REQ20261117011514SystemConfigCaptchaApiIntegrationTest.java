package com.acme.platform.system;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261117-011514 系统配置验证码开关读写（platform-system 管理端既有路径扩展）服务域集成测试
 * （Testcontainers PG，装配口径照抄 {@code REQ20261116230354SystemConfigApiIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-4——PUT 更新 captchaEnabled=true 后 GET 返回 true（缺省视图 false→更新生效），
 * 乐观锁冲突语义不变（匹配 version +1 / 陈旧 version 业务失败且值不回改）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>wire 字段名按契约固定：AdminSystemConfigUpdate.captchaEnabled（AdminSystemConfigView 同名回读）。</li>
 *   <li>乐观锁编排沿用 REQ230354 口径：无行缺省 version="0"（upsert 建行 created 行 version=0），
 *       匹配 version 修改两项（本用例含 captchaEnabled）→ version +1；陈旧 version → 业务失败
 *       code≠0（失败码沿用既有 STALE_WRITE/CONCURRENCY_CONFLICT 族，不钉死字面量，HTTP 200 失败信封）。</li>
 *   <li>认证/权限面不变：GET 认证即可读、PUT 经 super_admin（V15 已绑定 system:config:update）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req011514-config-it-secret-0123456"
})
class REQ20261117011514SystemConfigCaptchaApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
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
        jdbcTemplate.update("delete from sys_config");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_config");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-4：PUT captchaEnabled=true → GET 回读一致（缺省视图 false → 更新生效） ----------

    @Test
    void should_returnUpdatedCaptchaEnabled_when_putThenGet() {
        JsonNode created = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, true, 60_000, "0"))));
        assertThat(created.path("code").asText()).isEqualTo("0");
        assertThat(created.path("data").path("captchaEnabled").asBoolean())
                .as("无行缺省视图 captchaEnabled=false，PUT true 后应生效").isTrue();
        assertThat(created.path("data").path("watermarkEnabled").asBoolean())
                .as("同请求共字段一并生效").isTrue();
        assertThat(created.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(60_000);

        JsonNode read = call(get("/admin/v1/system/config"));
        assertThat(read.path("code").asText()).isEqualTo("0");
        assertThat(read.path("data").path("captchaEnabled").asBoolean()).isTrue();
        assertThat(read.path("data").path("version").asText()).isEqualTo("0");
    }

    // ---------- AC-4：乐观锁冲突语义不变（陈旧 version 业务失败且值不回改） ----------

    @Test
    void should_rejectStaleVersionWithoutValueChange_when_staleVersionRejected() {
        // 无行建行（version=0）
        call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, true, 60_000, "0"))));
        // 匹配 version=0 修改（含 captchaEnabled=false）→ version bump 1
        JsonNode matched = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(false, false, 300_000, "0"))));
        assertThat(matched.path("code").asText()).isEqualTo("0");
        assertThat(matched.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(matched.path("data").path("version").asText()).isEqualTo("1");

        // 陈旧 version=0 再 PUT → 业务失败 code≠0（既有失败码族，不钉死字面量）
        JsonNode stale = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, true, 60_000, "0"))));
        assertThat(stale.path("success").asBoolean()).isFalse();
        assertThat(stale.path("code").asText()).isNotEqualTo("0");

        // 陈旧写不得回改值
        JsonNode after = call(get("/admin/v1/system/config"));
        assertThat(after.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(after.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(300_000);
        assertThat(after.path("data").path("version").asText()).isEqualTo("1");
    }

    // ---------- 支撑 ----------

    private static Map<String, Object> configBody(boolean captchaEnabled, boolean watermarkEnabled,
                                                  int lockIdleTimeoutMs, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("captchaEnabled", captchaEnabled);
        body.put("watermarkEnabled", watermarkEnabled);
        body.put("lockIdleTimeoutMs", lockIdleTimeoutMs);
        body.put("footerCopyright", null);
        body.put("footerVisible", false);
        body.put("version", version);
        return body;
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) {
        MvcResult result = perform(request, "1", "default", Set.of("super_admin"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        try {
            return bodyOf(result);
        } catch (IOException ex) {
            throw new IllegalStateException("响应体解析失败：" + request, ex);
        }
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request,
                              String userId, String tenantId, Set<String> roles) {
        try {
            return mvc.perform(request
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject(userId, tenantId, Channel.B, roles)))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
