package com.acme.platform.auth.api;

import java.time.Instant;

/**
 * 本人在线会话视图（REQ-20261119-082531 确认记录④ §API GET /admin/v1/auth/me/sessions 行形态；
 * 非分页数组行，全部免权限点仅本人）：
 * 来源=AuthSession 本人 ACTIVE 未过期集合；ip/lastActiveAt 取会话信息哈希（缺失回退 issuedAt）；
 * current=当前请求所持访问令牌（Authorization Bearer jti）匹配标记。
 * wire 形状（frontend SelfSessionView 对齐口径）：tokenId/ip/issuedAt/lastActiveAt/current。
 *
 * @param tokenId      会话 id（AuthSession token_id，=访问令牌 jti；自助下线端点路径参数）
 * @param ip           登录来源 IP（会话信息缺失为 null）
 * @param issuedAt     登录时间（会话签发时间）
 * @param lastActiveAt 最近活跃（网关每请求更新；缺失回退 issuedAt）
 * @param current      是否当前会话（当前请求所持令牌对应行）
 */
public record SelfSessionView(String tokenId, String ip, Instant issuedAt,
                              Instant lastActiveAt, boolean current) {
}
