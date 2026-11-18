package com.acme.platform.auth.api;

/**
 * 租户会话批量撤销结果（REQ-20261117-025200 确认记录②）。
 * wire 面向服务间：计数字段用 int——全局 Long→String 序列化模块只针对 Long 类型
 * （id / version 语义字段），int 计数不经该模块、保持数值 JSON 形态，消费方按 int 解码。
 *
 * @param revokedCount 撤销集写入成功数
 * @param failedCount  撤销集写入失败数（提供方降级 WARN 继续，不回滚底账 KICKED，确认记录①）
 */
public record TenantSessionRevokeView(int revokedCount, int failedCount) {

    /** 无活跃会话（未知租户 / 已全量撤销）的幂等空结果（契约 §API：unknown tenant → 0/0 成功返回）。 */
    public static TenantSessionRevokeView empty() {
        return new TenantSessionRevokeView(0, 0);
    }
}
