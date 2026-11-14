package com.acme.platform.infra.application;

import com.acme.common.core.BizException;
import com.acme.framework.excel.support.ExcelImportResult;
import com.acme.framework.excel.support.ExcelReader;
import com.acme.framework.excel.support.ExcelWriter;
import com.acme.platform.infra.InfraError;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Excel 通用范式 demo 用例（REQ-20261114-071607 §API，导入导出不接示范域——仅通用能力封装 +
 * demo 端点，不落任何业务表）：复用 framework-excel 的 StreamExcelReader/Writer（经 ExcelReader/
 * ExcelWriter 门面）。
 *
 * <ul>
 *   <li>模板：表头 + 一行示例（demo-template）；</li>
 *   <li>导出：确定性 20 行 DemoRow（同输入同输出，AC-9）；</li>
 *   <li>导入：行级校验（名称非空、数量为 ≥0 数值）validate-only 不落库，
 *       错误明细逐行 row/field/message（AC-10）；文件无法解析 → EXCEL_PARSE_FAILED（AC-11）。</li>
 * </ul>
 */
@Service
public class ExcelDemoUseCase {

    /** demo 行数（确定性导出）。 */
    static final int DEMO_ROW_COUNT = 20;

    /** 固定文档属性（AC-9 确定性：承载库写出的 docProps/core.xml 内嵌创建/修改时间戳，替换为固定内容）。 */
    private static final String FIXED_CORE_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" \
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" \
            xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\
            <dc:creator>acme</dc:creator><cp:lastModifiedBy>acme</cp:lastModifiedBy>\
            <dcterms:created xsi:type="dcterms:W3CDTF">2020-01-01T00:00:00Z</dcterms:created>\
            <dcterms:modified xsi:type="dcterms:W3CDTF">2020-01-01T00:00:00Z</dcterms:modified>\
            </cp:coreProperties>""";

    private static final String CORE_XML_ENTRY = "docProps/core.xml";

    /** 归一后条目时间戳（固定 DOS 纪元，保证条目元数据同样确定）。 */
    private static final long FIXED_ENTRY_TIME = 0L;

    private final ExcelWriter excelWriter;
    private final ExcelReader excelReader;

    public ExcelDemoUseCase(ExcelWriter excelWriter, ExcelReader excelReader) {
        this.excelWriter = excelWriter;
        this.excelReader = excelReader;
    }

    /** 模板行集（表头 + 一行示例）。 */
    public List<DemoRow> templateRows() {
        return List.of(new DemoRow("示例名称", 1, "示例备注"));
    }

    /** 模板 xlsx 字节（表头 + 一行示例，AC-8；确定性归一）。 */
    public byte[] templateBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelWriter.write(templateRows(), DemoRow.class, out);
        return deterministicOutput(out.toByteArray());
    }

    /** 确定性导出行集（20 行 DemoRow，行内值仅依赖序号）。 */
    public List<DemoRow> exportRows() {
        return demoRows();
    }

    /** 确定性导出 xlsx 字节（20 行 DemoRow，同输入同输出，AC-9；确定性归一）。 */
    public byte[] exportBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelWriter.write(demoRows(), DemoRow.class, out);
        return deterministicOutput(out.toByteArray());
    }

    /**
     * 确定性归一（AC-9「同输入同输出」）：承载库（FastExcel/POI）写出的 xlsx 在
     * {@code docProps/core.xml} 内嵌创建/修改时间戳（随当前时刻变化），导致两次写出字节不一致。
     * 此处按原条目顺序重建 zip：core.xml 替换为固定内容、条目时间戳固定，其余条目字节原样保留。
     * 仅影响 demo 端点出参（框架 StreamExcelWriter 行为不在本轮变更范围）。
     */
    static byte[] deterministicOutput(byte[] xlsx) {
        Path temp = null;
        try {
            // 经 ZipFile（中央目录，条目尺寸可靠）重建——承载库 zip 用 data descriptor（流式头无尺寸），
            // ZipInputStream 流式解析在严格校验下不可靠
            temp = Files.createTempFile("req20261114071607-demo-", ".xlsx");
            Files.write(temp, xlsx);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipFile zipFile = new ZipFile(temp.toFile());
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                byte[] buffer = new byte[8192];
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(FIXED_ENTRY_TIME);
                    zip.putNextEntry(copy);
                    if (CORE_XML_ENTRY.equals(entry.getName())) {
                        zip.write(FIXED_CORE_XML.getBytes(StandardCharsets.UTF_8));
                    } else {
                        try (InputStream content = zipFile.getInputStream(entry)) {
                            int read;
                            while ((read = content.read(buffer)) > 0) {
                                zip.write(buffer, 0, read);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Excel 导出确定性归一失败", ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailure) {
                    // 临时文件清理失败不阻断（系统临时目录兜底回收）
                }
            }
        }
    }

    /** validate-only 导入（不落库）：total/valid/errors[{row,field,message}]（AC-10）。 */
    public ImportReport importReport(byte[] content) {
        ExcelImportResult<DemoRowOutcome> parsed;
        try {
            parsed = excelReader.read(new ByteArrayInputStream(content), ExcelDemoUseCase::parseRow);
        } catch (BizException ex) {
            // framework-excel 文件非法语义（SYS-12-0002）→ 契约 EXCEL_PARSE_FAILED（AC-11）
            throw new BizException(InfraError.EXCEL_PARSE_FAILED, null, ex);
        }
        return toReport(parsed);
    }

    /** 确定性 20 行（行内值仅依赖序号，无时钟/随机源）。 */
    static List<DemoRow> demoRows() {
        List<DemoRow> rows = new ArrayList<>(DEMO_ROW_COUNT);
        for (int i = 1; i <= DEMO_ROW_COUNT; i++) {
            rows.add(new DemoRow("演示项目-" + i, i, "备注-" + i));
        }
        return rows;
    }

    /** 单行解析 + 行级校验（名称非空、数量为 ≥0 数值；错误明细 field=字段名，裁决⑦）。 */
    private static DemoRowOutcome parseRow(Map<Integer, String> cells, int rowNum) {
        String name = text(cells, 0);
        String quantityText = text(cells, 1);
        String remark = text(cells, 2);
        List<ImportReport.RowError> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add(new ImportReport.RowError(rowNum, "name", "名称不能为空"));
        }
        Integer quantity = null;
        if (quantityText == null || quantityText.isBlank()) {
            problems.add(new ImportReport.RowError(rowNum, "quantity", "数量必须为数值"));
        } else {
            try {
                quantity = Integer.valueOf(quantityText.trim());
                if (quantity < 0) {
                    problems.add(new ImportReport.RowError(rowNum, "quantity", "数量不能为负数"));
                }
            } catch (NumberFormatException ex) {
                problems.add(new ImportReport.RowError(rowNum, "quantity", "数量必须为数值"));
            }
        }
        return new DemoRowOutcome(rowNum, new DemoRow(name, quantity, remark), problems);
    }

    private static ImportReport toReport(ExcelImportResult<DemoRowOutcome> parsed) {
        int valid = 0;
        List<ImportReport.RowError> errors = new ArrayList<>();
        for (DemoRowOutcome outcome : parsed.records()) {
            if (outcome.problems().isEmpty()) {
                valid++;
            } else {
                errors.addAll(outcome.problems());
            }
        }
        // COLLECT 口径下 reader 行级错误与本用例行级校验错误合并计数（本用例 mapper 不抛错，恒空）
        for (ExcelImportResult.ExcelRowError rowError : parsed.rowErrors()) {
            errors.add(new ImportReport.RowError(rowError.rowNum(), "行", rowError.message()));
        }
        return new ImportReport(parsed.records().size(), valid, errors);
    }

    private static String text(Map<Integer, String> cells, int column) {
        String value = cells.get(column);
        return value == null ? null : value.trim();
    }

    /** 单行解析中间态（行号 + 行 + 行级问题；不落库语义的行级载体）。 */
    record DemoRowOutcome(int rowNum, DemoRow row, List<ImportReport.RowError> problems) {
    }
}
