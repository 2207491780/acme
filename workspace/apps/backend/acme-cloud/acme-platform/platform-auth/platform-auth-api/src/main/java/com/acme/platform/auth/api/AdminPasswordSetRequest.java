package com.acme.platform.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 密码设置 / 重置请求（FR-005：明文仅传输态，服务端 BCrypt 不可逆存储；任何响应与日志不回显）。
 * 长度策略（8~64）在应用层校验并按契约返回 PASSWORD_POLICY_VIOLATED（openapi 400 语义），
 * DTO 仅校验非空（缺失请求体仍走通用校验失败映射）。
 */
public record AdminPasswordSetRequest(@NotBlank String password) {
}
