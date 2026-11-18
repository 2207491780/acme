package com.acme.framework.excel.support;

import com.acme.common.core.BizException;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.excel.internal.SheetMapping;
import com.acme.framework.excel.internal.StreamExcelWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 导出写门面（FR-004/AC-004）：业务声明映射（注解或 {@link ExcelMapper} 回调）→ 流式写出；
 * framework 无任何业务字段映射（BR-010）；映射非法/缺失显式失败 SYS-12-0001，不产出半截文件。
 */
public class ExcelWriter {

    private final ExcelProperties properties;

    public ExcelWriter(ExcelProperties properties) {
        this.properties = properties;
    }

    /** 按注解映射写出。 */
    public void write(List<?> records, Class<?> type, OutputStream out) {
        SheetMapping.Mapping mapping = SheetMapping.resolve(type);
        try (StreamExcelWriter writer = StreamExcelWriter.open(out, mapping.sheetName(), mapping.headers())) {
            int written = 0;
            for (Object record : records) {
                if (++written > properties.getMaxRows()) {
                    throw new BizException(ExcelError.ROW_LIMIT_EXCEEDED);
                }
                writer.writeRecord(record, mapping);
            }
        }
    }

    /** 按回调映射写出（Q-D001 可选面）。 */
    public <T> void write(List<T> records, ExcelMapper<T> mapper, OutputStream out) {
        if (mapper == null || mapper.headers() == null || mapper.headers().isEmpty()) {
            throw new BizException(ExcelError.MAPPING_INVALID);
        }
        try (StreamExcelWriter writer = StreamExcelWriter.open(out, "Sheet1", mapper.headers())) {
            int written = 0;
            for (T record : records) {
                if (++written > properties.getMaxRows()) {
                    throw new BizException(ExcelError.ROW_LIMIT_EXCEEDED);
                }
                writer.writeMapped(record, mapper);
            }
        }
    }
}
