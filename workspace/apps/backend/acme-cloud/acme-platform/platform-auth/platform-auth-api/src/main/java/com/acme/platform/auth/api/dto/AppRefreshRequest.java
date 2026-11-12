package com.acme.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * C 端刷新令牌请求（一次性轮换：旧刷新令牌即用即废）。
 */
public record AppRefreshRequest(@NotBlank String refreshToken) {
}
