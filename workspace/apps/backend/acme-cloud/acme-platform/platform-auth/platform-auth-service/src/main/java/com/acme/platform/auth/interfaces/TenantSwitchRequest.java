package com.acme.platform.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 超管跨租户切换请求（REQ-20261117-025200 确认记录④；认证端点 POST /admin/v1/auth/tenant-switch，
 * 服务内接口层 VO）。
 *
 * @param targetTenantCode 目标租户代码（必须 ENABLED 且未过期；可切换性由用例实查校验）
 */
public record TenantSwitchRequest(@NotBlank @Size(min = 1, max = 64) String targetTenantCode) {
}
