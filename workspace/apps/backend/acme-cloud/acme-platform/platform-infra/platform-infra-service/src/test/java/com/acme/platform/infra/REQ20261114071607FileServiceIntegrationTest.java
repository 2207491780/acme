package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-071607 文件服务（本地盘）服务域集成测试（Testcontainers PG + Redis，照抄
 * {@code SystemIntegrationTest}/{@code InfraIntegrationTest} 装配口径；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 上传成功闭环、AC-2 超限/类型拒绝、AC-3 下载字节一致、AC-4 删除幂等、
 * AC-5 缺权限统一拒绝、AC-6（服务侧）对象按 ObjectKey 落盘目录确定；
 * 边界补充：FILE_NOT_FOUND、文件列表冒烟。</p>
 *
 * <p>口径说明（契约歧义就地标注，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}（不断言 4xx，HTTP 200 语义）。</li>
 *   <li>HTTP 层经 MockMvc + 内签令牌（X-Acme-Token/X-Acme-Channel）驱动，权限判定桩件
 *       {@link AuthorizationGateway} 默认放行，AC-5 用例内切换为拒绝。</li>
 *   <li>大小上限用例按 {@code acme.storage.upload.max-size=1KB} 收紧，构造 2KB 载荷触发业务侧
 *       FILE_SIZE_EXCEEDED（避开 servlet multipart 容器上限）。</li>
 *   <li>xlsx 白名单匹配口径（MIME 映射 or 扩展名）契约未指明：用例同时使用标准 xlsx MIME 与
 *       .xlsx 原名，两种口径下均应放行。</li>
 *   <li>Content-Disposition 文件名编码（非 ASCII RFC 5987）契约未指明：用例用 ASCII 原名规避。</li>
 *   <li>FileView JSON 字段命名策略（驼峰/下划线）契约未指明：断言经 {@link #textOf} 双兼容取值。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-file-it-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme",
        "acme.storage.upload.max-size=1KB"
})
class REQ20261114071607FileServiceIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器（委派单 PG+Redis 口径；stream 关闭时当前装配不消费，属性预留注入）。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 本地盘存储根（静态初始化保证早于 Spring 上下文装配，@DynamicPropertySource 引用）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req20261114071607-file-it");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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

    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 装配真实鉴权过滤器（内部令牌校验）+ 真实 MVC 拦截器链（含 RequirePermissionInterceptor）；
        // 权限判定远程网关桩件默认放行，AC-5 用例内切换为拒绝。
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() throws IOException {
        // 共享环境脏数据 teardown：物理清表（绕开过滤面）+ 清空本地盘对象
        jdbcTemplate.update("delete from sys_file");
        jdbcTemplate.update("delete from sys_job_execution");
        jdbcTemplate.update("delete from shedlock_locks");
        clearStorageRoot();
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：上传合法文件 → FileView + 对象存在 + sys_file 记录 ----------

    @Test
    void should_returnFileViewAndPersistObjectAndRecord_when_uploadValidXlsx() throws Exception {
        byte[] content = "req-it-xlsx-content-2026".getBytes(StandardCharsets.UTF_8);
        JsonNode body = upload("hello.xlsx", XLSX_MIME, content, "req-it", "42");

        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.path("id").asText()).isNotBlank();
        assertThat(textOf(data, "originalName", "original_name")).isEqualTo("hello.xlsx");
        assertThat(data.path("size").asLong()).isEqualTo(content.length);
        assertThat(textOf(data, "contentType", "content_type")).isEqualTo(XLSX_MIME);
        assertThat(textOf(data, "bizType", "biz_type")).isEqualTo("req-it");
        assertThat(textOf(data, "bizId", "biz_id")).isEqualTo("42");
        assertThat(data.path("url").asText()).startsWith("http://files.integration.test/acme");
        assertThat(textOf(data, "createdAt", "created_at")).isNotBlank();

        // sys_file 有记录
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isEqualTo(1L);

        // AC-6（服务侧）：对象按 ObjectKey 落盘（root-dir + object_key 路径确定）
        String objectKey = jdbcTemplate.queryForObject("select object_key from sys_file", String.class);
        assertThat(objectKey).isNotBlank();
        Path objectPath = STORAGE_ROOT.resolve(objectKey);
        assertThat(objectPath).exists();
        assertThat(Files.readAllBytes(objectPath)).isEqualTo(content);
    }

    // ---------- AC-2：超限/类型白名单拒绝，无对象无记录 ----------

    @Test
    void should_rejectWithFileSizeExceededAndNothingPersisted_when_fileExceedsMaxSize() throws Exception {
        byte[] oversized = new byte[2 * 1024]; // 2KB > 用例收紧后的 1KB 上限（远低于 servlet 默认上限）
        JsonNode body = upload("big.txt", "text/plain", oversized);

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("FILE_SIZE_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
        assertThat(anyObjectFileExists()).isFalse();
    }

    @Test
    void should_rejectWithFileTypeRejectedAndNothingPersisted_when_contentTypeOutsideWhitelist() throws Exception {
        JsonNode body = upload("evil.zip", "application/zip", "zip-content".getBytes(StandardCharsets.UTF_8));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("FILE_TYPE_REJECTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
        assertThat(anyObjectFileExists()).isFalse();
    }

    // ---------- AC-3：下载字节一致 + Content-Disposition 原名 ----------

    @Test
    void should_returnIdenticalBytesWithOriginalName_when_downloadContent() throws Exception {
        byte[] content = "qa-download-bytes-2026-11-14".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded = upload("qa-report.txt", "text/plain", content);
        String id = uploaded.path("data").path("id").asText();

        MvcResult result = call(get("/admin/v1/infra/files/{id}/content", id));

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("qa-report.txt");
    }

    // ---------- 边界：不存在 → FILE_NOT_FOUND ----------

    @Test
    void should_returnFileNotFound_when_getMetadataOrContentOfMissingId() throws Exception {
        JsonNode meta = bodyOf(call(get("/admin/v1/infra/files/{id}", "99999")));
        assertThat(meta.path("code").asText()).isEqualTo("FILE_NOT_FOUND");

        JsonNode content = bodyOf(call(get("/admin/v1/infra/files/{id}/content", "99999")));
        assertThat(content.path("code").asText()).isEqualTo("FILE_NOT_FOUND");
    }

    // ---------- AC-4：删除幂等（已删/对象不存在均成功） ----------

    @Test
    void should_succeedSilently_when_deleteAlreadyDeletedFile() throws Exception {
        JsonNode uploaded = upload("to-delete.txt", "text/plain",
                "delete-me".getBytes(StandardCharsets.UTF_8));
        String id = uploaded.path("data").path("id").asText();

        JsonNode first = bodyOf(call(delete("/admin/v1/infra/files/{id}", id)));
        assertThat(first.path("code").asText()).isEqualTo("0");
        // 软删：记录仍在（deleted=true）；对象已 remove
        assertThat(jdbcTemplate.queryForObject("select deleted from sys_file where id=?",
                Boolean.class, Long.parseLong(id))).isTrue();
        String objectKey = jdbcTemplate.queryForObject("select object_key from sys_file where id=?",
                String.class, Long.parseLong(id));
        assertThat(STORAGE_ROOT.resolve(objectKey)).doesNotExist();

        // 幂等：再次删除仍成功（记录已删 + 对象不存在）
        JsonNode second = bodyOf(call(delete("/admin/v1/infra/files/{id}", id)));
        assertThat(second.path("code").asText()).isEqualTo("0");
    }

    // ---------- AC-5：缺对应权限点 → 统一拒绝（接口级权限生效） ----------

    @Test
    void should_rejectWithUnifiedNonZeroCode_when_requiredPermissionMissing() throws Exception {
        reset(authorizationGateway);
        doThrow(new BizException(PERMISSION_DENIED)).when(authorizationGateway).requirePermission(anyString());

        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                get("/admin/v1/infra/files"),
                multipart("/admin/v1/infra/files").file(new MockMultipartFile(
                        "file", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))),
                get("/admin/v1/infra/files/{id}/content", "1"),
                delete("/admin/v1/infra/files/{id}", "1"),
                get("/admin/v1/infra/jobs"),
                post("/admin/v1/infra/jobs/{jobKey}/run", "file-retention-cleanup"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = bodyOf(call(endpoint));
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint.toString()).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint.toString())
                    .isNotEqualTo("0");
        }
    }

    // ---------- 补充（无 AC 对应）：文件列表分页冒烟 ----------

    @Test
    void should_listUploadedFile_when_pageQueryAfterUpload() throws Exception {
        JsonNode uploaded = upload("list-me.txt", "text/plain", "list".getBytes(StandardCharsets.UTF_8));
        String id = uploaded.path("data").path("id").asText();

        JsonNode page = bodyOf(call(get("/admin/v1/infra/files")));
        assertThat(page.path("code").asText()).isEqualTo("0");
        JsonNode records = page.path("data").path("records");
        boolean found = false;
        for (JsonNode record : records) {
            if (record.path("id").asText().equals(id)) {
                found = true;
            }
        }
        assertThat(found).as("上传后的文件应出现在分页列表").isTrue();
    }

    // ---------- 支撑 ----------

    /** AC-5 权限拒绝桩件错误码（真实拒绝码由安全层定义，用例仅断言 Result.code≠0 语义）。 */
    private static final ErrorCode PERMISSION_DENIED = new ErrorCode() {
        @Override
        public String code() {
            return "PERMISSION_DENIED";
        }

        @Override
        public String defaultMessage() {
            return "无权限（集成测试桩）";
        }
    };

    private JsonNode upload(String originalName, String contentType, byte[] content) throws Exception {
        return upload(originalName, contentType, content, null, null);
    }

    private JsonNode upload(String originalName, String contentType, byte[] content,
                            String bizType, String bizId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", originalName, contentType, content);
        AbstractMockHttpServletRequestBuilder<?> request = multipart("/admin/v1/infra/files").file(file);
        if (bizType != null) {
            request.param("bizType", bizType);
        }
        if (bizId != null) {
            request.param("bizId", bizId);
        }
        return bodyOf(call(request));
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token())
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private String token() {
        return tokenIssuer.issue(new AuthenticatedSubject("42", "t-1", Channel.B, Set.of("admin")));
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private boolean anyObjectFileExists() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return false;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            return paths.anyMatch(Files::isRegularFile);
        }
    }

    private void clearStorageRoot() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(STORAGE_ROOT))
                    .forEach(path -> path.toFile().delete());
        }
    }

    /** FileView JSON 字段命名（驼峰/下划线）双兼容取值。 */
    private static String textOf(JsonNode node, String camel, String snake) {
        if (node.hasNonNull(camel)) {
            return node.get(camel).asText();
        }
        if (node.hasNonNull(snake)) {
            return node.get(snake).asText();
        }
        return null;
    }
}
