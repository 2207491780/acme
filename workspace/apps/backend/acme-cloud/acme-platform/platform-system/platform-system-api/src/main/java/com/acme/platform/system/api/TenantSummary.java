package com.acme.platform.system.api;

/**
 * 租户摘要（登录页租户下拉契约，login-tenant-select）：
 * tenantCode 为登录提交值（SysUser.tenantId 存租户代码，画像与令牌 claims 同口径）。
 */
public record TenantSummary(String tenantCode, String tenantName) {
}
