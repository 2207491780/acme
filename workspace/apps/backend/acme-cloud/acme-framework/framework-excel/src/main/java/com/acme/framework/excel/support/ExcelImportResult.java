package com.acme.framework.excel.support;

import java.util.List;

/**
 * 导入结果（FR-005/AC-005：records + rowErrors；默认 COLLECT 汇总行级错误，Q-D004）。
 */
public record ExcelImportResult<T>(List<T> records, List<ExcelRowError> rowErrors) {

    public ExcelImportResult {
        records = records == null ? List.of() : List.copyOf(records);
        rowErrors = rowErrors == null ? List.of() : List.copyOf(rowErrors);
    }

    /** 行级错误（行号 1 起 + 原因；单行非法不静默吞掉）。 */
    public record ExcelRowError(int rowNum, String message) {
    }
}
