package com.acme.platform.auth.api;

import java.time.Instant;

/**
 * 在线用户视图（REQ-20261114-120105 §API GET /admin/v1/auth/online-users 行形态；非分页数组行）：
 * 来源=AuthSession 未过期集合；username 经 platform-system 画像解析（C 端=手机号本地解析）；
 * ip 取登录时写入的会话信息；lastActiveAt=每请求活跃（网关逐请求更新，权威口径 delta1），
 * 会话信息缺失回退 loginAt。wire 形状（frontend 对齐口径）：sessionId/username/userType/ip/loginAt/lastActiveAt。
 *
 * @param sessionId    会话 id（AuthSession token_id，踢下线端点路径参数）
 * @param username     用户名（B 端；C 端为手机号；画像缺失为 null）
 * @param ip           登录来源 IP（会话信息缺失为 null）
 * @param loginAt      登录时间（会话签发时间）
 * @param lastActiveAt 最近活跃（网关每请求更新；缺失回退 loginAt）
 * @param userType     端别（B/C）
 */
public record AdminOnlineUserView(String sessionId, String username, String ip,
                                  Instant loginAt, Instant lastActiveAt, String userType) {
}
