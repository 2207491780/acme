package com.acme.platform.infra.api;

/**
 * 审计动作分布统计项（REQ-20261119-011524 确认记录①②）：GET /internal/v1/audit-stats/distribution
 * 响应行——动作编码与窗口内事件条数（按条数降序、top N 截断）。platform-system 首页聚合消费方
 * 自行映射为本域视图。count 经 LongToStringModule wire 为字符串（全局 Long→string 规约）。
 *
 * @param action 审计动作编码（acme_op_audit.action）
 * @param count  窗口内该动作事件条数
 */
public record ActionCount(String action, long count) {
}
