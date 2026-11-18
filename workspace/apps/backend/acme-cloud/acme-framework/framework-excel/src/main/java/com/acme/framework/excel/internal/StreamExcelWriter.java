package com.acme.framework.excel.internal;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.support.ExcelTypeEnum;
import cn.idev.excel.write.metadata.WriteSheet;
import com.acme.framework.excel.support.ExcelMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式写出（FR-004/AC-004、NFR-003：xlsx 流式，不整表驻留内存；承载库 FastExcel，Q-D001）。
 * 写出中断（响应已提交）→ ERROR 日志 + 连接中断，不静默当成功（架构设计 6.2）。
 */
public class StreamExcelWriter implements AutoCloseable {

    private final ExcelWriter writer;
    private final WriteSheet sheet;
    private final List<String> headers;

    private StreamExcelWriter(ExcelWriter writer, WriteSheet sheet, List<String> headers) {
        this.writer = writer;
        this.sheet = sheet;
        this.headers = headers;
    }

    /** 开始写出（表头先行）。 */
    public static StreamExcelWriter open(OutputStream out, String sheetName, List<String> headers) {
        ExcelWriter writer = FastExcel.write(out)
                .excelType(ExcelTypeEnum.XLSX)
                .head(headers.stream().map(List::of).toList())
                .autoCloseStream(false)
                .build();
        WriteSheet sheet = FastExcel.writerSheet(sheetName == null ? "Sheet1" : sheetName).build();
        return new StreamExcelWriter(writer, sheet, headers);
    }

    /** 追加一批行（列值与 headers 对齐）。 */
    public void writeRows(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        writer.write(rows, sheet);
    }

    /** 追加一条记录（注解映射）。 */
    public void writeRecord(Object record, SheetMapping.Mapping mapping) {
        List<Object> row = new ArrayList<>(mapping.columns().size());
        for (SheetMapping.Column column : mapping.columns()) {
            row.add(column.read(record));
        }
        writer.write(List.of(row), sheet);
    }

    /** 追加一条记录（回调映射，Q-D001）。 */
    public <T> void writeMapped(T record, ExcelMapper<T> mapper) {
        writer.write(List.of(mapper.toRow(record)), sheet);
    }

    /** 表头（只读）。 */
    public List<String> headers() {
        return headers;
    }

    @Override
    public void close() {
        try {
            writer.finish();
        } catch (RuntimeException ex) {
            // 写出中断（响应已提交场景）：ERROR 由调用方日志兜底，不静默当成功
            throw new UncheckedIOException(new IOException("Excel 写出中断", ex));
        }
    }
}
