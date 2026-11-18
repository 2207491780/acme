package com.acme.platform.auth.api;

import java.time.Instant;

/**
 * 用户令牌签发结果（FR-002、Q-003 B 端单会话：签发即互踢旧会话；不携带 refreshToken）。
 *
 * @param accessToken JWS HS256 访问令牌（Authorization: Bearer &lt;accessToken&gt;）
 * @param tokenType   令牌类型（Bearer）
 * @param expiresAt   过期时间（默认签发后 30 分钟短会话）
 * @param userId      用户 ID（字符串化雪花 ID）
 * @param username    用户名
 * @param nickname    昵称（展示用途，可空）
 */
public record AdminTokenIssued(String accessToken, String tokenType, Instant expiresAt,
                               Long userId, String username, String nickname) {

    /** 令牌类型固定 Bearer（openapi 契约）。 */
    public AdminTokenIssued {
        tokenType = tokenType == null ? "Bearer" : tokenType;
    }
}
