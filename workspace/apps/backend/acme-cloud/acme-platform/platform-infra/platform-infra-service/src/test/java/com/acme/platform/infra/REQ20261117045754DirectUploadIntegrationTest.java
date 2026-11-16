package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261117-045754 对象存储直传与租户配额（LOCAL 模式）服务域集成测试（Testcontainers PG + Redis，
 * 照抄 {@code REQ20261114071607FileServiceIntegrationTest} 装配口径；无 Docker 自动跳过）。
 *
 * <p>AC→用例映射：</p>
 * <ul>
 *   <li>AC-1 直传三段闭环 + sys_file 6 段 objectKey + 幂等：
 *       {@link #should_completeLocalDirectUploadChain_when_preparationThenTicketPutThenCompletion}、
 *       {@link #should_returnSameFileViewWithoutNewRow_when_completionRepeated}；
 *       边界：{@link #should_rejectCompletionWithSizeMismatch_when_sizeDiffersFromPreparation}（SIZE_MISMATCH）、
 *       {@link #should_rejectCompletionWithStorageObjectMissing_when_objectNeverUploaded}（STORAGE_OBJECT_MISSING）。</li>
 *   <li>AC-2 票据三态（已消费/租户不符/过期）+ 伪造 + 对象未写入：
 *       {@link #should_rejectReplayPutWithTicketInvalid_when_ticketAlreadyConsumed}、
 *       {@link #should_rejectPutWithTicketInvalid_when_ticketBoundToAnotherTenant}、
 *       {@link #should_rejectPutWithTicketInvalid_when_ticketNeverIssued}、
 *       {@link #should_rejectPutWithTicketInvalid_when_ticketExpiredInRedis}。</li>
 *   <li>AC-3 超配额（preparation 与 multipart 双路）+ 配额内正常：
 *       {@link #should_rejectPreparationWithStorageQuotaExceeded_when_declaredSizeExceedsTenantQuota}、
 *       {@link #should_rejectMultipartUploadWithStorageQuotaExceeded_when_tenantQuotaAlreadyConsumed}。</li>
 *   <li>AC-4 图片语义（completion 与 multipart 双路；坏图/超 max-image-px 拒绝、正常图通过）：
 *       {@link #should_acceptValidPngThroughDirectChain_when_withinMaxImagePx}、
 *       {@link #should_rejectCompletionWithStorageImageInvalid_when_objectBytesNotDecodable}、
 *       {@link #should_rejectCompletionWithStorageImageInvalid_when_imageExceedsMaxImagePx}、
 *       {@link #should_rejectMultipartUploadWithStorageImageInvalid_when_imageExceedsMaxImagePx}、
 *       {@link #should_acceptMultipartPng_when_withinMaxImagePx}。</li>
 *   <li>AC-6 补充断言（存量 5 段 objectKey 行 元数据/下载/清理 兼容解析不回归）：
 *       {@link #should_keepMetadataDownloadAndCleanupCompatible_when_rowHasLegacyFiveSegmentObjectKey}
 *       （既有面回归引用 {@code REQ20261114071607FileServiceIntegrationTest} 全部用例）。</li>
 *   <li>AC-7 既有语义经 preparation 触发：
 *       {@link #should_rejectPreparationWithFileSizeExceeded_when_declaredSizeAboveMaxSize}、
 *       {@link #should_rejectPreparationWithFileTypeRejected_when_contentTypeOutsideWhitelist}。</li>
 *   <li>租户隔离（契约确认记录①②⑥）：
 *       {@link #should_rejectForeignCompletion_when_objectKeyTenantSegmentMismatchesTenantContext}、
 *       {@link #should_assembleDistinctTenantSegments_when_twoTenantsPrepare}。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义就地标注 AMB，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}（勿断言 4xx，HTTP 200 语义）。</li>
 *   <li>AMB-1 错误码 wire 字面量取委派单口径：STORAGE_QUOTA_EXCEEDED / IMAGE_INVALID / TICKET_INVALID /
 *       SIZE_MISMATCH（+契约 API 节明示的 STORAGE_OBJECT_MISSING）。注意契约 API 节用无前缀
 *       QUOTA_EXCEEDED、确认记录③④用 STORAGE_ 前缀（STORAGE_QUOTA_EXCEEDED/STORAGE_IMAGE_INVALID）：
 *       三向核对后只需改类顶部 {@code CODE_*} 常量。</li>
 *   <li>AMB-2 票据过期以「扫描删除 Redis 票据键」模拟 TTL 过期（等价于过期键被 Redis 清除后的未命中；
 *       票据键名格式为实现内部细节，契约未指明）；跨租户票据以「租户上下文切换 + 他人票据」表达
 *       ticket 绑定租户语义（等价于直插他租户值，且不依赖键名格式）。</li>
 *   <li>AMB-3 跨租户 completion 拒绝码：契约仅「FORBIDDEN 语义」未给字面量 → 断言 code≠0。</li>
 *   <li>AMB-4 objectKey 租户段假定 = token tenantId（TenantContext 直通）；若实现解析租户 code，
 *       调整 {@code TENANT_A/TENANT_B} 常量与租户段断言；AC-6 legacy 行 tenant_id 同此口径。</li>
 *   <li>AMB-5 preparation.headers 的 Content-Type 键名大小写契约未指明 → 不区分大小写匹配且值=contentType。</li>
 *   <li>PUT 与 preparation 的 Content-Type 不一致的行为契约未指明 → 不测（留 TL 审查）。</li>
 *   <li>PNG 字节经 ImageIO 运行时生成（1x1=通过 / 2x2=超 1px 注入上限），坏图用非图像字节
 *       （替代硬编码字节数组，规避 CRC 手工风险；宽高边界按确认记录④「宽高 ≤ max-image-px」）。</li>
 *   <li>测试属性注入契约键：acme.storage.quota.default-bytes=1536（配额）、acme.storage.upload.max-size=1KB、
 *       acme.storage.upload.max-image-px=1；载荷避开 1KB 边界（seed=1000B）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-direct-it-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme",
        "acme.storage.upload.max-size=1KB",
        "acme.storage.quota.default-bytes=1536",
        "acme.storage.upload.max-image-px=1"
})
class REQ20261117045754DirectUploadIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String PREPARE_URI = "/admin/v1/infra/files/direct-upload-preparations";
    private static final String COMPLETE_URI = "/admin/v1/infra/files/direct-upload-completions";
    private static final String TICKET_PUT_URI = "/admin/v1/infra/files/direct-uploads/{ticket}";

    /** AMB-1：新错误码 wire 字面量（委派单口径；契约 API 节/确认记录有出入时三向核对后统一改这里）。 */
    private static final String CODE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED";
    private static final String CODE_IMAGE_INVALID = "IMAGE_INVALID";
    private static final String CODE_TICKET_INVALID = "TICKET_INVALID";
    private static final String CODE_SIZE_MISMATCH = "SIZE_MISMATCH";
    private static final String CODE_OBJECT_MISSING = "STORAGE_OBJECT_MISSING";

    /** AMB-4：token tenantId 直通为 objectKey 租户段与 sys_file.tenant_id 的假定值。 */
    private static final String TENANT_A = "t-1";
    private static final String TENANT_B = "t-2";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 本地盘存储根（静态初始化保证早于 Spring 上下文装配，@DynamicPropertySource 引用）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req20261117045754-direct-it");
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 真实鉴权过滤器 + 真实 MVC 拦截器链；权限判定桩件默认放行（权限面回归由既有用例覆盖）。
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() throws IOException {
        // 共享环境脏数据 teardown：物理清表 + 清空本地盘对象 + 清空 Redis 票据键
        jdbcTemplate.update("delete from sys_file");
        jdbcTemplate.update("delete from sys_job_execution");
        jdbcTemplate.update("delete from shedlock_locks");
        clearRedis();
        clearStorageRoot();
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：LOCAL 直传三段闭环 → FileView 落库 + objectKey 含租户段（6 段） ----------

    @Test
    void should_completeLocalDirectUploadChain_when_preparationThenTicketPutThenCompletion() throws Exception {
        byte[] content = "req20261117045754-direct-xlsx".getBytes(StandardCharsets.UTF_8);

        JsonNode prep = prepare("hello.xlsx", XLSX_MIME, content.length, "req-it", "42");
        assertThat(prep.path("success").asBoolean()).isTrue();
        assertThat(prep.path("code").asText()).isEqualTo("0");
        JsonNode plan = prep.path("data");
        String objectKey = plan.path("objectKey").asText();
        assertThat(objectKey).isNotBlank();
        String[] segments = objectKey.split("/");
        assertThat(segments).as("objectKey 应为 6 段（files/{tenant}/yyyy/MM/dd/name）").hasSize(6);
        assertThat(segments[0]).isEqualTo("files");
        assertThat(segments[1]).as("AMB-4：租户段=TenantContext 租户").isEqualTo(TENANT_A);
        assertThat(segments[2]).matches("\\d{4}");
        assertThat(segments[3]).matches("\\d{2}");
        assertThat(segments[4]).matches("\\d{2}");
        assertThat(segments[5]).isNotBlank();
        String ticket = plan.path("ticket").asText();
        assertThat(ticket).as("LOCAL 模式应返回服务端一次性票据").isNotBlank();
        assertThat(plan.path("method").asText()).isEqualTo("PUT");
        assertThat(plan.path("uploadUrl").asText()).isNotBlank();
        assertThat(plan.path("expiresInSeconds").asLong())
                .as("票据有效期应为正且 ≤ 5min（确认记录① TTL）").isBetween(1L, 300L);
        assertHeaderContainsContentType(plan.path("headers"), XLSX_MIME);

        JsonNode putResult = putTicket(ticket, content, XLSX_MIME);
        assertThat(putResult.path("code").asText()).isEqualTo("0");
        Path objectPath = STORAGE_ROOT.resolve(objectKey);
        assertThat(objectPath).exists();
        assertThat(Files.readAllBytes(objectPath)).isEqualTo(content);

        JsonNode completion = complete(objectKey, "hello.xlsx", XLSX_MIME, content.length, "req-it", "42");
        assertThat(completion.path("code").asText()).isEqualTo("0");
        JsonNode file = completion.path("data");
        assertThat(file.path("id").asText()).isNotBlank();
        assertThat(textOf(file, "originalName", "original_name")).isEqualTo("hello.xlsx");
        assertThat(file.path("size").asLong()).isEqualTo(content.length);
        assertThat(textOf(file, "contentType", "content_type")).isEqualTo(XLSX_MIME);
        assertThat(textOf(file, "bizType", "biz_type")).isEqualTo("req-it");
        assertThat(textOf(file, "bizId", "biz_id")).isEqualTo("42");
        assertThat(textOf(file, "createdAt", "created_at")).isNotBlank();

        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select object_key from sys_file", String.class))
                .as("sys_file 落库 objectKey 应为 6 段新格式").isEqualTo(objectKey);
    }

    @Test
    void should_returnSameFileViewWithoutNewRow_when_completionRepeated() throws Exception {
        byte[] content = "req20261117045754-idempotent".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("idempotent.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        String ticket = prep.path("data").path("ticket").asText();
        assertThat(putTicket(ticket, content, "text/plain").path("code").asText()).isEqualTo("0");

        JsonNode first = complete(objectKey, "idempotent.txt", "text/plain", content.length, null, null);
        assertThat(first.path("code").asText()).isEqualTo("0");

        JsonNode second = complete(objectKey, "idempotent.txt", "text/plain", content.length, null, null);
        assertThat(second.path("code").asText()).isEqualTo("0");
        assertThat(second.path("data").path("id").asText()).isEqualTo(first.path("data").path("id").asText());
        assertThat(textOf(second.path("data"), "originalName", "original_name"))
                .isEqualTo(textOf(first.path("data"), "originalName", "original_name"));
        assertThat(second.path("data").path("size").asLong()).isEqualTo(content.length);
        assertThat(textOf(second.path("data"), "url", "url"))
                .isEqualTo(textOf(first.path("data"), "url", "url"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class))
                .as("幂等 completion 不得重复落库").isEqualTo(1L);
    }

    // ---------- AC-1 边界：completion 的 exists / size 一致校验 ----------

    @Test
    void should_rejectCompletionWithSizeMismatch_when_sizeDiffersFromPreparation() throws Exception {
        byte[] content = "size-guard".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("size-guard.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        assertThat(putTicket(prep.path("data").path("ticket").asText(), content, "text/plain")
                .path("code").asText()).isEqualTo("0");

        JsonNode completion = complete(objectKey, "size-guard.txt", "text/plain",
                content.length + 1, null, null);

        assertThat(completion.path("code").asText()).isEqualTo(CODE_SIZE_MISMATCH);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    @Test
    void should_rejectCompletionWithStorageObjectMissing_when_objectNeverUploaded() throws Exception {
        JsonNode prep = prepare("missing.txt", "text/plain", 10, null, null);
        assertThat(prep.path("code").asText()).isEqualTo("0");

        JsonNode completion = complete(prep.path("data").path("objectKey").asText(),
                "missing.txt", "text/plain", 10, null, null);

        assertThat(completion.path("code").asText()).isEqualTo(CODE_OBJECT_MISSING);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    // ---------- AC-2：票据三态（已消费/租户不符/过期）+ 伪造 → TICKET_INVALID 且对象未写入 ----------

    @Test
    void should_rejectReplayPutWithTicketInvalid_when_ticketAlreadyConsumed() throws Exception {
        byte[] content = "first-put-bytes".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("consume-me.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        String ticket = prep.path("data").path("ticket").asText();
        assertThat(putTicket(ticket, content, "text/plain").path("code").asText()).isEqualTo("0");
        Path objectPath = STORAGE_ROOT.resolve(objectKey);
        assertThat(objectPath).exists();

        byte[] replay = "replay-must-not-overwrite".getBytes(StandardCharsets.UTF_8);
        JsonNode replayPut = putTicket(ticket, replay, "text/plain");

        assertThat(replayPut.path("code").asText()).isEqualTo(CODE_TICKET_INVALID);
        assertThat(Files.readAllBytes(objectPath))
                .as("单次消费语义：重放不得覆写已写入对象").isEqualTo(content);
    }

    @Test
    void should_rejectPutWithTicketInvalid_when_ticketBoundToAnotherTenant() throws Exception {
        byte[] content = "tenant-a-only".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("tenant-a.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        String ticket = prep.path("data").path("ticket").asText();

        JsonNode foreignPut = putTicketAs(token(TENANT_B), ticket, content, "text/plain");

        assertThat(foreignPut.path("code").asText())
                .as("AMB-2：ticket 绑定租户（等价于直插他租户票据值）").isEqualTo(CODE_TICKET_INVALID);
        assertThat(STORAGE_ROOT.resolve(objectKey)).doesNotExist();
    }

    @Test
    void should_rejectPutWithTicketInvalid_when_ticketNeverIssued() throws Exception {
        JsonNode forgedPut = putTicket("forged-ticket-never-issued", "x".getBytes(StandardCharsets.UTF_8),
                "text/plain");

        assertThat(forgedPut.path("code").asText()).isEqualTo(CODE_TICKET_INVALID);
        assertThat(anyObjectFileExists()).isFalse();
    }

    @Test
    void should_rejectPutWithTicketInvalid_when_ticketExpiredInRedis() throws Exception {
        byte[] content = "expired-ticket".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("expired.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        String ticket = prep.path("data").path("ticket").asText();

        deleteRedisKeysReferencing(ticket);

        JsonNode expiredPut = putTicket(ticket, content, "text/plain");
        assertThat(expiredPut.path("code").asText())
                .as("AMB-2：TTL 过期等价模拟（删除票据键后未命中=失效）").isEqualTo(CODE_TICKET_INVALID);
        assertThat(STORAGE_ROOT.resolve(objectKey)).doesNotExist();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    // ---------- AC-3：配额（preparation 与 multipart 双路；quota=1536，seed=1000B 避开 1KB 边界） ----------

    @Test
    void should_rejectPreparationWithStorageQuotaExceeded_when_declaredSizeExceedsTenantQuota() throws Exception {
        assertThat(upload("seed.txt", "text/plain", new byte[1000]).path("code").asText()).isEqualTo("0");

        JsonNode over = prepare("over.txt", "text/plain", 1000, null, null);
        assertThat(over.path("code").asText()).isEqualTo(CODE_QUOTA_EXCEEDED);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class))
                .as("超配额 preparation 不得落库").isEqualTo(1L);

        JsonNode within = prepare("within.txt", "text/plain", 400, null, null);
        assertThat(within.path("code").asText()).as("配额内（1000+400 ≤ 1536）应放行").isEqualTo("0");
    }

    @Test
    void should_rejectMultipartUploadWithStorageQuotaExceeded_when_tenantQuotaAlreadyConsumed() throws Exception {
        assertThat(upload("seed.txt", "text/plain", new byte[1000]).path("code").asText()).isEqualTo("0");

        JsonNode over = upload("over.txt", "text/plain", new byte[1000]);

        assertThat(over.path("code").asText()).as("multipart 与 preparation 同检配额").isEqualTo(CODE_QUOTA_EXCEEDED);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isEqualTo(1L);
    }

    // ---------- AC-4：图片语义（completion 与 multipart 双路；max-image-px 注入 1px） ----------

    @Test
    void should_acceptValidPngThroughDirectChain_when_withinMaxImagePx() throws Exception {
        byte[] png = pngBytes(1, 1);
        JsonNode prep = prepare("pixel.png", "image/png", png.length, null, null);
        assertThat(prep.path("code").asText()).isEqualTo("0");
        assertThat(putTicket(prep.path("data").path("ticket").asText(), png, "image/png")
                .path("code").asText()).isEqualTo("0");

        JsonNode completion = complete(prep.path("data").path("objectKey").asText(),
                "pixel.png", "image/png", png.length, null, null);

        assertThat(completion.path("code").asText()).as("1x1 ≤ 注入上限 1px 应通过").isEqualTo("0");
        assertThat(textOf(completion.path("data"), "contentType", "content_type")).isEqualTo("image/png");
    }

    @Test
    void should_rejectCompletionWithStorageImageInvalid_when_objectBytesNotDecodable() throws Exception {
        byte[] garbage = "not-a-real-image-at-all".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("broken.png", "image/png", garbage.length, null, null);
        assertThat(putTicket(prep.path("data").path("ticket").asText(), garbage, "image/png")
                .path("code").asText()).isEqualTo("0");

        JsonNode completion = complete(prep.path("data").path("objectKey").asText(), "broken.png",
                "image/png", garbage.length, null, null);

        assertThat(completion.path("code").asText()).isEqualTo(CODE_IMAGE_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    @Test
    void should_rejectCompletionWithStorageImageInvalid_when_imageExceedsMaxImagePx() throws Exception {
        byte[] png = pngBytes(2, 2);
        JsonNode prep = prepare("big.png", "image/png", png.length, null, null);
        assertThat(putTicket(prep.path("data").path("ticket").asText(), png, "image/png")
                .path("code").asText()).isEqualTo("0");

        JsonNode completion = complete(prep.path("data").path("objectKey").asText(),
                "big.png", "image/png", png.length, null, null);

        assertThat(completion.path("code").asText())
                .as("2x2 > 注入上限 1px 应拒绝").isEqualTo(CODE_IMAGE_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    @Test
    void should_rejectMultipartUploadWithStorageImageInvalid_when_imageExceedsMaxImagePx() throws Exception {
        JsonNode body = upload("big.png", "image/png", pngBytes(2, 2));

        assertThat(body.path("code").asText()).as("multipart 与 direct-completion 双路同检图片语义")
                .isEqualTo(CODE_IMAGE_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
        assertThat(anyObjectFileExists()).isFalse();
    }

    @Test
    void should_acceptMultipartPng_when_withinMaxImagePx() throws Exception {
        JsonNode body = upload("pixel.png", "image/png", pngBytes(1, 1));

        assertThat(body.path("code").asText()).isEqualTo("0");
    }

    // ---------- AC-7：既有语义经 preparation 触发（大小上限 / 白名单） ----------

    @Test
    void should_rejectPreparationWithFileSizeExceeded_when_declaredSizeAboveMaxSize() throws Exception {
        JsonNode body = prepare("big.txt", "text/plain", 2 * 1024, null, null);

        assertThat(body.path("code").asText()).isEqualTo("FILE_SIZE_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
        assertThat(anyObjectFileExists()).isFalse();
    }

    @Test
    void should_rejectPreparationWithFileTypeRejected_when_contentTypeOutsideWhitelist() throws Exception {
        JsonNode body = prepare("evil.zip", "application/zip", 16, null, null);

        assertThat(body.path("code").asText()).isEqualTo("FILE_TYPE_REJECTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
        assertThat(anyObjectFileExists()).isFalse();
    }

    // ---------- 租户隔离：completion 租户段比对 + 双租户租户段组装 ----------

    @Test
    void should_rejectForeignCompletion_when_objectKeyTenantSegmentMismatchesTenantContext() throws Exception {
        byte[] content = "cross-tenant-guard".getBytes(StandardCharsets.UTF_8);
        JsonNode prep = prepare("owned.txt", "text/plain", content.length, null, null);
        String objectKey = prep.path("data").path("objectKey").asText();
        assertThat(putTicket(prep.path("data").path("ticket").asText(), content, "text/plain")
                .path("code").asText()).isEqualTo("0");
        assertThat(complete(objectKey, "owned.txt", "text/plain", content.length, null, null)
                .path("code").asText()).isEqualTo("0");

        JsonNode foreign = completeAs(token(TENANT_B), objectKey, "owned.txt", "text/plain",
                content.length);

        assertThat(foreign.path("success").asBoolean()).isFalse();
        assertThat(foreign.path("code").asText())
                .as("AMB-3：契约 FORBIDDEN 语义，未给字面量 → 断言 code≠0").isNotEqualTo("0");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class))
                .as("跨租户 completion 不得新增租户 B 行").isEqualTo(1L);
    }

    @Test
    void should_assembleDistinctTenantSegments_when_twoTenantsPrepare() throws Exception {
        JsonNode prepA = prepare("segment.txt", "text/plain", 10, null, null);
        JsonNode prepB = prepareAs(token(TENANT_B), "segment.txt", "text/plain", 10);

        String keyA = prepA.path("data").path("objectKey").asText();
        String keyB = prepB.path("data").path("objectKey").asText();
        assertThat(keyA.split("/")).hasSize(6);
        assertThat(keyB.split("/")).hasSize(6);
        assertThat(keyA.split("/")[0]).isEqualTo("files");
        assertThat(keyB.split("/")[0]).isEqualTo("files");
        assertThat(keyA.split("/")[1]).as("AMB-4").isEqualTo(TENANT_A);
        assertThat(keyB.split("/")[1]).as("AMB-4").isEqualTo(TENANT_B);
        assertThat(keyA.split("/")[1]).isNotEqualTo(keyB.split("/")[1]);
        assertThat(keyA).isNotEqualTo(keyB);
    }

    // ---------- AC-6 补充：存量 5 段 objectKey 行 元数据/下载/清理 兼容解析 ----------

    @Test
    void should_keepMetadataDownloadAndCleanupCompatible_when_rowHasLegacyFiveSegmentObjectKey()
            throws Exception {
        byte[] content = "legacy-five-segment-object".getBytes(StandardCharsets.UTF_8);
        String legacyObjectKey = "files/2026/11/14/legacy-qa-report.txt";
        Path objectPath = STORAGE_ROOT.resolve(legacyObjectKey);
        Files.createDirectories(objectPath.getParent());
        Files.write(objectPath, content);
        jdbcTemplate.update("""
                insert into sys_file (id, bucket, object_key, original_name, size, content_type,
                                      biz_type, biz_id, tenant_id, uploaded_by,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (990001, 'acme', ?, 'legacy-qa-report.txt', ?, 'text/plain',
                        'req-it', '7', ?, 'op-1',
                        now(), 'it', now(), 'it', 0, false)
                """, legacyObjectKey, (long) content.length, TENANT_A);

        JsonNode meta = bodyOf(call(get("/admin/v1/infra/files/{id}", "990001")));
        assertThat(meta.path("code").asText()).as("5 段旧键行元数据可读").isEqualTo("0");
        assertThat(textOf(meta.path("data"), "originalName", "original_name"))
                .isEqualTo("legacy-qa-report.txt");
        assertThat(meta.path("data").path("size").asLong()).isEqualTo(content.length);

        MvcResult contentResult = call(get("/admin/v1/infra/files/{id}/content", "990001"));
        assertThat(contentResult.getResponse().getContentAsByteArray())
                .as("5 段旧键下载字节一致（objectKeyOf 兼容解析）").isEqualTo(content);

        JsonNode removal = bodyOf(call(delete("/admin/v1/infra/files/{id}", "990001")));
        assertThat(removal.path("code").asText()).as("5 段旧键清理（删除）兼容").isEqualTo("0");
        assertThat(objectPath).doesNotExist();
    }

    // ---------- 支撑 ----------

    private JsonNode prepare(String fileName, String contentType, long size, String bizType, String bizId)
            throws Exception {
        return prepareAs(token(), fileName, contentType, size, bizType, bizId);
    }

    private JsonNode prepareAs(String token, String fileName, String contentType, long size) throws Exception {
        return prepareAs(token, fileName, contentType, size, null, null);
    }

    private JsonNode prepareAs(String token, String fileName, String contentType, long size,
                               String bizType, String bizId) throws Exception {
        return callAsBody(token, jsonPost(PREPARE_URI, fields(
                "fileName", fileName, "contentType", contentType, "size", size,
                "bizType", bizType, "bizId", bizId)));
    }

    private JsonNode complete(String objectKey, String fileName, String contentType, long size,
                              String bizType, String bizId) throws Exception {
        return completeAs(token(), objectKey, fileName, contentType, size, bizType, bizId);
    }

    private JsonNode completeAs(String token, String objectKey, String fileName, String contentType,
                                long size) throws Exception {
        return completeAs(token, objectKey, fileName, contentType, size, null, null);
    }

    private JsonNode completeAs(String token, String objectKey, String fileName, String contentType,
                                long size, String bizType, String bizId) throws Exception {
        return callAsBody(token, jsonPost(COMPLETE_URI, fields(
                "objectKey", objectKey, "fileName", fileName, "contentType", contentType, "size", size,
                "bizType", bizType, "bizId", bizId)));
    }

    private JsonNode putTicket(String ticket, byte[] content, String contentType) throws Exception {
        return putTicketAs(token(), ticket, content, contentType);
    }

    private JsonNode putTicketAs(String token, String ticket, byte[] content, String contentType)
            throws Exception {
        return callAsBody(token, put(TICKET_PUT_URI, ticket)
                .content(content)
                .contentType(MediaType.parseMediaType(contentType)));
    }

    private JsonNode upload(String originalName, String contentType, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", originalName, contentType, content);
        return bodyOf(call(multipart("/admin/v1/infra/files").file(file)));
    }

    private MockHttpServletRequestBuilder jsonPost(String uri, Map<String, Object> body) {
        return post(uri).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }

    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put((String) keyValues[i], value);
            }
        }
        return map;
    }

    /** AMB-5：headers 含 Content-Type（键名大小写不敏感），值与 preparation contentType 一致。 */
    private static void assertHeaderContainsContentType(JsonNode headers, String expected) {
        assertThat(headers.isObject()).as("preparation.headers 应为对象").isTrue();
        boolean matched = false;
        for (String name : headers.propertyNames()) {
            if ("content-type".equalsIgnoreCase(name)) {
                assertThat(headers.path(name).asText()).isEqualTo(expected);
                matched = true;
            }
        }
        assertThat(matched).as("headers 应含 Content-Type（值=%s）", expected).isTrue();
    }

    private String token() {
        return token(TENANT_A);
    }

    private String token(String tenantId) {
        return tokenIssuer.issue(new AuthenticatedSubject("42", tenantId, Channel.B, Set.of("admin")));
    }

    private JsonNode callAsBody(String token, AbstractMockHttpServletRequestBuilder<?> request)
            throws Exception {
        return bodyOf(mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token)
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn());
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token())
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void deleteRedisKeysReferencing(String ticket) {
        Set<String> keys = stringRedisTemplate.keys("*");
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            boolean hit = key.contains(ticket);
            if (!hit) {
                try {
                    String value = stringRedisTemplate.opsForValue().get(key);
                    hit = value != null && value.contains(ticket);
                } catch (RuntimeException ignored) {
                    // 非字符串序列化值读不出：键名含 ticket 已覆盖主流实现（RedisDedupStore 风格）
                }
            }
            if (hit) {
                stringRedisTemplate.delete(key);
            }
        }
    }

    private void clearRedis() {
        Set<String> keys = stringRedisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
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

    /** 运行时生成合法 PNG（宽 x 高）；替代硬编码字节数组，规避 CRC 手工风险（AMB）。 */
    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", out)).as("PNG writer 应可用").isTrue();
        return out.toByteArray();
    }

    /** FileView JSON 字段命名（驼峰/下划线）双兼容取值（沿用既有口径）。 */
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
