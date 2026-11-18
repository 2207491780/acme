package com.acme.platform.auth.api;

/**
 * 批量下线结果视图（REQ-20261117-061017 确认⑥ DELETE /admin/v1/auth/online-users?username=）：
 * kickedCount=被踢会话数（撤销集写入生效）；skippedCount=超管保护跳过数（操作者非超管时，
 * 超管会话跳过并计数）。
 *
 * @param kickedCount  被踢会话数
 * @param skippedCount 超管会话跳过数
 */
public record BatchKickView(long kickedCount, long skippedCount) {
}
