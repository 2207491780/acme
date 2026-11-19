package com.acme.platform.system.api.dto;

/**
 * 首页登录趋势统计点（REQ-20261119-011524 确认记录①）：GET /admin/v1/system/dashboard/summary
 * 响应行——日期（YYYY-MM-DD）与当日登录日志条数。数据源为 platform-auth internal 统计契约
 * （LoginLogStatsPoint），本域独立定义同名形状 record（两 api 模块不互相依赖，消费方分别映射）。
 * count 经 LongToStringModule wire 为字符串（全局 Long→string 规约）。
 *
 * @param date  统计日（YYYY-MM-DD）
 * @param count 当日登录日志条数
 */
public record LoginTrendPoint(String date, long count) {
}
