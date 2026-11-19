package com.acme.platform.auth.api;

/**
 * 登录日志按日统计点（REQ-20261119-011524 确认记录①②）：GET /internal/v1/login-log-stats
 * 响应行——日期（YYYY-MM-DD，按 sys_login_log.created_at 所在日聚合）与当日登录日志条数
 * （成功+失败不分 channel 全量口径）。platform-system 首页聚合消费方自行映射为本域视图。
 * count 经 LongToStringModule wire 为字符串（全局 Long→string 规约）。
 *
 * @param date  统计日（YYYY-MM-DD）
 * @param count 当日登录日志条数
 */
public record LoginLogStatsPoint(String date, long count) {
}
