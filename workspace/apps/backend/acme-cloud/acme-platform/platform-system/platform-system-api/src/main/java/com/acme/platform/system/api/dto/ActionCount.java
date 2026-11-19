package com.acme.platform.system.api.dto;

/**
 * 首页审计动作分布统计项（REQ-20261119-011524 确认记录①）：GET /admin/v1/system/dashboard/summary
 * 响应行——动作编码与窗口内事件条数（按条数降序）。数据源为 platform-infra internal 统计契约
 * （同名 ActionCount），本域独立定义同名形状 record（两 api 模块不互相依赖，消费方分别映射）。
 * count 经 LongToStringModule wire 为字符串（全局 Long→string 规约）。
 *
 * @param action 审计动作编码（acme_op_audit.action）
 * @param count  窗口内该动作事件条数
 */
public record ActionCount(String action, long count) {
}
