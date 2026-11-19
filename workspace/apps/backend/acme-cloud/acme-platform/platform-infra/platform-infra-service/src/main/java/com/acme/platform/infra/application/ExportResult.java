package com.acme.platform.infra.application;

import com.acme.framework.excel.config.ExcelProperties;

import java.util.List;

/**
 * 导出批结果（REQ-20261117-061017 确认④）：记录集（上限内截断后）与截断标记——控制器据此加
 * {@code X-Export-Truncated: true} 响应头并给文件名加 {@code .part} 后缀。
 * S14 收敛审计/异常日志两处同款「取 cap+1 行 → 截断标记」导出骨架为本工厂。
 *
 * @param records   导出记录（≤上限）
 * @param truncated 是否超上限截断
 */
public record ExportResult<T>(List<T> records, boolean truncated) {

    /** 导出上限（确认④：10000 行）。 */
    static final long MAX_ROWS = 10_000L;

    /**
     * 导出行上限（确认④ 10000；并受 acme.excel.max-rows 收窄，防与写面 ROW_LIMIT 冲突）。
     */
    static long cap(ExcelProperties excelProperties) {
        return Math.min(MAX_ROWS, Math.max(excelProperties.getMaxRows(), 1L));
    }

    /**
     * 按上限截断构造（取数面按 {@code cap + 1} 拉取，超出即 truncated=true）。
     */
    static <T> ExportResult<T> of(List<T> fetched, long cap) {
        boolean truncated = fetched.size() > cap;
        List<T> records = truncated ? fetched.subList(0, (int) cap) : fetched;
        return new ExportResult<>(records, truncated);
    }
}
