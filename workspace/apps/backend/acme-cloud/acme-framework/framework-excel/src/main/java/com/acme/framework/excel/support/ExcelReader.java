package com.acme.framework.excel.support;

import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.excel.internal.StreamExcelReader;

import java.io.InputStream;

/**
 * 导入读门面（FR-005/AC-005）：流式读入 + 行级错误按 {@code import-error-policy} 处置
 * （默认 COLLECT 汇总，Q-D004）；单行非法不静默吞掉。
 */
public class ExcelReader {

    private final ExcelProperties properties;

    public ExcelReader(ExcelProperties properties) {
        this.properties = properties;
    }

    /** 按注解映射导入。 */
    public <T> ExcelImportResult<T> read(InputStream in, Class<T> type) {
        return StreamExcelReader.read(in, type, properties);
    }

    /** 按回调解析导入。 */
    public <T> ExcelImportResult<T> read(InputStream in, ExcelRowMapper<T> rowMapper) {
        return StreamExcelReader.read(in, rowMapper, properties);
    }
}
