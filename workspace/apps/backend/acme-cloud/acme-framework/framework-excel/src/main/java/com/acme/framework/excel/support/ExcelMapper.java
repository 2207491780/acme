package com.acme.framework.excel.support;

import java.util.List;

/**
 * 导出映射回调（Q-D001 可选面：注解映射之外的映射声明方式；FR-004）。
 */
public interface ExcelMapper<T> {

    /** 列头（顺序即列序）。 */
    List<String> headers();

    /** 记录 → 行单元格（与 headers 等长）。 */
    List<Object> toRow(T record);
}
