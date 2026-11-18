package com.acme.platform.infra;

import cn.idev.excel.FastExcel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelImportResult;
import com.acme.framework.excel.support.ExcelReader;
import com.acme.framework.excel.support.ExcelSheet;
import com.acme.framework.excel.support.ExcelWriter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * REQ-20261114-071607 Excel 通用范式 demo 契约/边界测试（Testcontainers PG + Redis，
 * 照抄 {@code InfraIntegrationTest} 装配口径；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-8 模板表头与 DemoRow 定义一致、AC-9 导出确定性 + 读回 20 行、
 * AC-10 非法行错误明细 validate-only 不落库、AC-11 无法解析文件 EXCEL_PARSE_FAILED。</p>
 *
 * <p>口径说明（契约歧义就地标注，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>demo 文件参数名沿用契约 upload 端点的 {@code file}（demo-import 契约仅写 multipart xlsx，
 *       参数名未显式指明）。</li>
 *   <li>模板表头断言经底层 FastExcel（cn.idev.excel，framework-excel 同源依赖）以
 *       headRowNumber(0) 读取首行，不依赖实现内部 SheetMapping 语义。</li>
 *   <li>demo-export 的 sheet 名契约未指明：读回探针按 @ExcelSheet("demo") 断言，
 *       若实现命名不同请同步探针注解。</li>
 *   <li>errors.row 取数据行号（FastExcel 跳表头后自 1 起，同 framework-excel 回路测试口径）；
 *       errors.field 取值口径（中文表头 vs 字段名）契约未指明，用例仅断言非空。</li>
 *   <li>"库无写入"以 sys_file 行数不变为代理断言（demo 无业务表，可判定面仅此）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req-excel-it-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme"
})
class REQ20261114071607ExcelDemoIntegrationTest {

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

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req20261114071607-excel-it");
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
    private com.acme.framework.security.support.InternalTokenVerifier tokenVerifier;

    @Autowired
    private com.acme.framework.security.support.InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.acme.platform.infra.infrastructure.client.AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new com.acme.framework.security.interceptor.InternalTokenAuthFilter(
                        tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_file");
        jdbcTemplate.update("delete from sys_job_execution");
        jdbcTemplate.update("delete from shedlock_locks");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-8：模板表头与 DemoRow 定义（名称/数量/备注）一致 ----------

    @Test
    void should_returnXlsxWithDemoRowHeaders_when_downloadDemoTemplate() throws Exception {
        byte[] template = call(get("/admin/v1/infra/excel/demo-template")).getResponse().getContentAsByteArray();

        List<Map<Integer, String>> rows = FastExcel.read(new ByteArrayInputStream(template))
                .sheet()
                .headRowNumber(0)
                .doReadSync();
        assertThat(rows).isNotEmpty();
        Map<Integer, String> headerCells = rows.get(0);
        List<String> headers = new ArrayList<>();
        for (int column = 0; headerCells.containsKey(column); column++) {
            headers.add(headerCells.get(column));
        }
        assertThat(headers).containsExactly("名称", "数量", "备注");
    }

    // ---------- AC-9：两次导出字节一致（确定性）且可读回 20 行 ----------

    @Test
    void should_returnByteIdenticalExportReadableAs20Rows_when_exportTwice() throws Exception {
        byte[] first = call(get("/admin/v1/infra/excel/demo-export")).getResponse().getContentAsByteArray();
        byte[] second = call(get("/admin/v1/infra/excel/demo-export")).getResponse().getContentAsByteArray();

        assertThat(second).as("同输入同输出：两次导出字节一致").isEqualTo(first);

        ExcelImportResult<DemoProbeRow> read = new ExcelReader(new ExcelProperties())
                .read(new ByteArrayInputStream(first), DemoProbeRow.class);
        assertThat(read.rowErrors()).isEmpty();
        assertThat(read.records()).as("导出应可读回 20 行 DemoRow").hasSize(20);
    }

    // ---------- AC-10：非法行（空名称/负数量）逐行错误明细，validate-only 不落库 ----------

    @Test
    void should_reportRowLevelErrorsWithoutPersisting_when_importRowsWithBlankNameOrNegativeQty() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExcelWriter(new ExcelProperties()).write(List.of(
                        new DemoProbeRow("合法行", 1, "ok"),
                        new DemoProbeRow("", 5, "空名称"),
                        new DemoProbeRow("负数量", -1, "neg")),
                DemoProbeRow.class, out);
        MockMultipartFile file = new MockMultipartFile("file", "demo-import.xlsx", XLSX_MIME, out.toByteArray());

        JsonNode body = bodyOf(call(multipart("/admin/v1/infra/excel/demo-import").file(file)));

        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.path("total").asInt()).isEqualTo(3);
        assertThat(data.path("valid").asInt()).isEqualTo(1);
        JsonNode errors = data.path("errors");
        assertThat(errors).as("两条非法行应逐行给出错误明细").hasSize(2);
        java.util.Set<Integer> errorRows = new java.util.HashSet<>();
        for (JsonNode error : errors) {
            assertThat(error.path("row").asInt()).as("错误明细应含行号").isPositive();
            assertThat(error.path("field").asText()).as("错误明细应含字段").isNotBlank();
            assertThat(error.path("message").asText()).as("错误明细应含提示").isNotBlank();
            errorRows.add(error.path("row").asInt());
        }
        // 数据行号自 1 起（表头跳过）：空名称=第 2 行，负数量=第 3 行
        assertThat(errorRows).containsExactlyInAnyOrder(2, 3);

        // validate-only：无任何落库（sys_file 行数不变作为可判定代理）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_file", Long.class)).isZero();
    }

    // ---------- AC-11：无法解析的文件 → EXCEL_PARSE_FAILED ----------

    @Test
    void should_returnExcelParseFailed_when_importUnparseableFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "broken.xlsx", XLSX_MIME,
                "not-an-xlsx".getBytes(StandardCharsets.UTF_8));

        JsonNode body = bodyOf(call(multipart("/admin/v1/infra/excel/demo-import").file(file)));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("EXCEL_PARSE_FAILED");
    }

    // ---------- 支撑 ----------

    /** DemoRow 探针（DemoRow=名称/数量/备注，契约 §API；仅测试域映射，不落库）。 */
    @ExcelSheet("demo")
    public static class DemoProbeRow {

        @ExcelField(value = "名称", order = 1)
        public String name;

        @ExcelField(value = "数量", order = 2)
        public Integer qty;

        @ExcelField(value = "备注", order = 3)
        public String remark;

        public DemoProbeRow() {
        }

        public DemoProbeRow(String name, Integer qty, String remark) {
            this.name = name;
            this.qty = qty;
            this.remark = remark;
        }
    }

    private MvcResult call(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request)
            throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new com.acme.framework.core.support.AuthenticatedSubject(
                                        "42", "t-1", com.acme.common.context.Channel.B,
                                        java.util.Set.of("admin"))))
                        .requestAttr(com.acme.framework.core.support.ClientContextDraft.ATTRIBUTE_NAME,
                                new com.acme.framework.core.support.ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
