package com.acme.framework.excel.support;

import com.acme.common.core.BizException;
import com.acme.framework.excel.config.ExcelProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导入导出测试（FR-004/005、AC-004/005：注解映射、行级错误口径 COLLECT/FAIL_FAST、损坏文件显式失败）。
 */
class ExcelRoundTripTest {

    @ExcelSheet("demo")
    public static class Row {
        @ExcelField(value = "名称", order = 1)
        public String name;
        @ExcelField(value = "数量", order = 2)
        public Integer qty;

        public Row() {
        }

        public Row(String name, Integer qty) {
            this.name = name;
            this.qty = qty;
        }
    }

    @Test
    void exportThenImportRoundTrips() {
        ExcelProperties properties = new ExcelProperties();
        ExcelWriter writer = new ExcelWriter(properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(List.of(new Row("a", 1), new Row("b", 2)), Row.class, out);

        ExcelReader reader = new ExcelReader(properties);
        ExcelImportResult<Row> result =
                reader.read(new ByteArrayInputStream(out.toByteArray()), Row.class);
        assertThat(result.rowErrors()).isEmpty();
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).name).isEqualTo("a");
        assertThat(result.records().get(1).qty).isEqualTo(2);
    }

    @Test
    void importCollectsRowErrorsByDefault() {
        ExcelProperties properties = new ExcelProperties();
        ExcelWriter writer = new ExcelWriter(properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(List.of(new Row("a", 1), new Row("bad", 2)), Row.class, out);
        byte[] bytes = out.toByteArray();

        // 回调解析：第二条数据行报错（FastExcel 默认跳过表头，数据行号 1 起）
        ExcelReader reader = new ExcelReader(properties);
        ExcelImportResult<String> result = reader.read(new ByteArrayInputStream(bytes), (cells, rowNum) -> {
            if (rowNum == 2) {
                throw new IllegalStateException("行数据非法");
            }
            return cells.get(0);
        });
        assertThat(result.records()).hasSize(1);
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).rowNum()).isEqualTo(2);
    }

    @Test
    void importFailFastThrowsOnFirstBadRow() {
        ExcelProperties properties = new ExcelProperties();
        properties.setImportErrorPolicy(ExcelProperties.ImportErrorPolicy.FAIL_FAST);
        ExcelWriter writer = new ExcelWriter(properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(List.of(new Row("a", 1)), Row.class, out);

        ExcelReader reader = new ExcelReader(properties);
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(out.toByteArray()), (cells, rowNum) -> {
            throw new IllegalStateException("行数据非法");
        })).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ExcelError.ROW_INVALID));
    }

    @Test
    void corruptedFileFailsExplicitly() {
        ExcelReader reader = new ExcelReader(new ExcelProperties());
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream("not-excel".getBytes()), Row.class))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ExcelError.FILE_INVALID));
    }

    @Test
    void missingMappingFailsExplicitlyWithoutHalfFile() {
        ExcelWriter writer = new ExcelWriter(new ExcelProperties());
        assertThatThrownBy(() -> writer.write(List.of("x"), Object.class, new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ExcelError.MAPPING_INVALID));
    }

    @Test
    void excelMapperCallbackExports() {
        ExcelProperties properties = new ExcelProperties();
        ExcelWriter writer = new ExcelWriter(properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(List.of("v1", "v2"), new ExcelMapper<String>() {
            @Override
            public List<String> headers() {
                return List.of("col1");
            }

            @Override
            public List<Object> toRow(String record) {
                return List.of(record);
            }
        }, out);
        assertThat(out.size()).isPositive();
    }
}
