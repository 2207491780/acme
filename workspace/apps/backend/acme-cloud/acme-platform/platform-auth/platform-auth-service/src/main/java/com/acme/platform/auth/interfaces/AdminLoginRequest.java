package com.acme.platform.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（B 端租户 + 账号密码 + 图形验证码，Q-002 本期唯一认证形态；服务内接口层 VO）。
 * tenantCode 来自登录页租户下拉（login-tenant-select，多租户口径：SysUser.tenantId 存租户代码）；
 * captchaId / captchaCode 来自 GET /admin/v1/auth/captcha（login-guard-captcha：防在线爆破，一次性消费）——
 * 条件必填：仅当租户验证码策略开启时必填（REQ-20261117-011514），由 LoginUseCase 统一强制
 * （blank → CAPTCHA_INVALID），避免 Bean Validation 泄露策略语义外的 400 口径。
 * password 明文仅经 HTTPS 传输，服务端只存 BCrypt 散列（FR-005），任何响应与日志不回显。
 */
public record AdminLoginRequest(@NotBlank @Size(min = 1, max = 64) String tenantCode,
                                @NotBlank @Size(min = 3, max = 64) String username,
                                @NotBlank @Size(min = 1, max = 64) String password,
                                @Size(max = 64) String captchaId,
                                @Size(max = 8) String captchaCode) {
}
