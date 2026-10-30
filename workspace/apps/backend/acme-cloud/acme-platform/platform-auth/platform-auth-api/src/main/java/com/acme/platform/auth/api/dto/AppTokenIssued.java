package com.acme.platform.auth.api.dto;

import java.time.Instant;

/**
 * C 端双令牌签发结果（11.4：C 端「令牌 + 刷新」；多端并存、一次性轮换）。
 *
 * @param accessToken       访问令牌（Authorization: Bearer；默认 2 小时）
 * @param refreshToken      刷新令牌（仅用于 /app/v1/auth/tokens/refresh；默认 14 天）
 * @param accessExpiresAt   访问令牌过期时间
 * @param refreshExpiresAt  刷新令牌过期时间
 * @param userId            C 端账户 ID（字符串化雪花 ID）
 * @param phone             手机号（脱敏展示由消费方负责，11.5.2）
 */
public record AppTokenIssued(String accessToken, String refreshToken,
                             Instant accessExpiresAt, Instant refreshExpiresAt,
                             Long userId, String phone) {
}
