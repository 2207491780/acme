package com.acme.framework.excel.support;

/**
 * 导入行解析回调（FR-005 可选面：注解映射之外的行解析方式）。
 *
 * @param <T> 记录类型
 */
@FunctionalInterface
public interface ExcelRowMapper<T> {

    /**
     * 解析一行。
     *
     * @param cells   行单元格（列序 → 文本）
     * @param rowNum  行号（1 起，含表头计数）
     */
    T map(java.util.Map<Integer, String> cells, int rowNum);
}
