package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 首页平台概览聚合视图（REQ-20261119-011524 确认记录①③⑥）：GET /admin/v1/system/dashboard/summary
 * 出参。六数字段 + 两趋势列表；Feign 下游（auth 在线数/登录趋势、infra 审计分布）失败降级为
 * 0/空列表（统计面 fail-open，前端以 — 展示，确认记录③），本地字段（unread=当前用户未读）恒准确。
 * 静态字段（users/roles/tenants/online/trend/distribution）服务端 60s 本地缓存，unread 实时取
 * （确认记录⑥：用户相关字段不入缓存）。
 * 数值经 LongToStringModule wire 为字符串（全局 Long→string 规约；计数字段上限富余）。
 *
 * @param users           全平台用户总数（sys_user，跨租户口径）
 * @param roles           全平台角色总数（sys_role，跨租户口径）
 * @param tenants         租户总数（sys_tenant 平台目录表）
 * @param onlineUsers     当前活跃会话数（auth_session ACTIVE 且未过期）
 * @param unreadMessages  当前用户未读站内信条数（实时，不入缓存）
 * @param loginTrend      近 14 日登录日志按日趋势（升序；下游失败为空列表）
 * @param auditTopActions 近 30 日审计动作分布 top 5（按条数降序；下游失败为空列表）
 */
public record DashboardSummaryView(
        Long users,
        Long roles,
        Long tenants,
        Long onlineUsers,
        Long unreadMessages,
        List<LoginTrendPoint> loginTrend,
        List<ActionCount> auditTopActions) {
}
