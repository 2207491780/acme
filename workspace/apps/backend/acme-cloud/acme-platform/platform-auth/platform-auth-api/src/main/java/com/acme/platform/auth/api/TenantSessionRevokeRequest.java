package com.acme.platform.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 租户会话批量撤销请求（REQ-20261117-025200 确认记录②；wire 形态 {@code {"tenantCode":...}}）。
 *
 * @param tenantCode 目标租户代码（sys_tenant.tenant_code 与 auth_session.tenant_id 同口径存租户代码）
 */
public record TenantSessionRevokeRequest(@NotBlank String tenantCode) {
}
