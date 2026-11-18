package com.acme.platform.auth.application;

import java.util.List;

/**
 * 导出批结果（REQ-20261117-061017 确认④）：记录集（上限内截断后）与截断标记——控制器据此加
 * {@code X-Export-Truncated: true} 响应头并给文件名加 {@code .part} 后缀。
 *
 * @param records   导出记录（≤上限）
 * @param truncated 是否超上限截断
 */
public record ExportResult<T>(List<T> records, boolean truncated) {
}
