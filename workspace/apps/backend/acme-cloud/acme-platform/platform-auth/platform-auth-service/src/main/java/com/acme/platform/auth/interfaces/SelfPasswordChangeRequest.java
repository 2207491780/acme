package com.acme.platform.auth.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助改密请求（REQ-20261119-082531 确认记录③；认证端点 PUT /admin/v1/auth/me/password，
 * 服务内接口层 VO，全部免权限点仅本人）：
 * oldPassword 旧密码（明文，用例 BCrypt matches——失败 OLD_PASSWORD_INVALID）；newPassword 新密码。
 * 长度策略（8~64）与新旧不同校验在应用层统一承载（AdminPasswordSetRequest 同款口径——
 * 违规统一 PASSWORD_POLICY_VIOLATED / SAME_PASSWORD，DTO 仅校验非空，避免同一策略两处两码）。
 * password 明文仅经 HTTPS 传输，服务端只存 BCrypt 散列（FR-005），任何响应与日志不回显。
 */
public record SelfPasswordChangeRequest(@NotBlank String oldPassword,
                                        @NotBlank String newPassword) {
}
