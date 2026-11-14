package com.acme.platform.infra.application;

import cn.idev.excel.FastExcel;
import com.acme.common.core.BizException;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.excel.support.ExcelReader;
import com.acme.framework.excel.support.ExcelImportResult;
import com.acme.framework.excel.support.ExcelWriter;
import com.acme.platform.infra.InfraError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Excel 通用范式 demo 用例单元测试（REQ-20261114-071607；纯 JUnit，framework-excel 真实现）：
 * 模板表头（AC-8）、导出确定性 + 20 行读回（AC-9）、行级校验明细 validate-only（AC-10）、
 * 无法解析文件 EXCEL_PARSE_FAILED（AC-11）。
 */
class ExcelDemoUseCaseTest {

    private ExcelDemoUseCase useCase;

    @BeforeEach
    void setUp() {
        ExcelProperties properties = new ExcelProperties();
        useCase = new ExcelDemoUseCase(new ExcelWriter(properties), new ExcelReader(properties));
    }

    @Test
    void templateCarriesDemoRowHeaders() {
        // AC-8：模板首行表头与 DemoRow 定义一致（名称/数量/备注），FastExcel headRowNumber(0) 读首行
        byte[] template = useCase.templateBytes();
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
        // 模板含一行示例
        assertThat(rows).hasSize(2);
    }

    @Test
    void exportIsDeterministicAndReadableAs20Rows() {
        // AC-9：两次调用字节一致（确定性）
        byte[] first = useCase.exportBytes();
        byte[] second = useCase.exportBytes();
        assertThat(second).isEqualTo(first);

        // 读回 20 行 DemoRow（StreamExcelReader 注解映射复用）
        ExcelImportResult<DemoRow> read = new ExcelReader(new ExcelProperties())
                .read(new ByteArrayInputStream(first), DemoRow.class);
        assertThat(read.rowErrors()).isEmpty();
        assertThat(read.records()).hasSize(20);
        assertThat(read.records().get(0).getName()).isEqualTo("演示项目-1");
        assertThat(read.records().get(19).getQuantity()).isEqualTo(20);
    }

    @Test
    void importReportsRowLevelErrorsWithoutPersisting() {
        // AC-10：空名称/负数量逐行给出 row/field/message，valid 计数正确（validate-only 无写入面）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExcelWriter(new ExcelProperties()).write(List.of(
                        new DemoRow("合法行", 1, "ok"),
                        new DemoRow("", 5, "空名称"),
                        new DemoRow("负数量", -1, "neg")),
                DemoRow.class, out);

        ImportReport report = useCase.importReport(out.toByteArray());

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.valid()).isEqualTo(1);
        assertThat(report.errors()).hasSize(2);
        Set<Integer> errorRows = new HashSet<>();
        for (ImportReport.RowError error : report.errors()) {
            assertThat(error.row()).isPositive();
            assertThat(error.field()).isNotBlank();
            assertThat(error.message()).isNotBlank();
            errorRows.add(error.row());
        }
        assertThat(errorRows).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void importOfUnparseableFileFailsWithExcelParseFailed() {
        // AC-11：无法解析文件 → EXCEL_PARSE_FAILED（framework FILE_INVALID 统一转契约码）
        assertThatThrownBy(() -> useCase.importReport("not-an-xlsx".getBytes()))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.EXCEL_PARSE_FAILED));
    }

    @Test
    void importOfAllValidRowsCountsAllValid() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExcelWriter(new ExcelProperties()).write(List.of(
                        new DemoRow("a", 0, null),
                        new DemoRow("b", 2, "x")),
                DemoRow.class, out);

        ImportReport report = useCase.importReport(out.toByteArray());
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.valid()).isEqualTo(2);
        assertThat(report.errors()).isEmpty();
    }
}
