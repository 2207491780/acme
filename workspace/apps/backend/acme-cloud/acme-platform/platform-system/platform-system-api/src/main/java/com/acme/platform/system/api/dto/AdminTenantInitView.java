package com.acme.platform.system.api.dto;

/**
 * 租户开通初始化结果（tenant-management-complete）：租户目录 + 初始管理员（凭据设置见请求 DTO 注）。
 */
public record AdminTenantInitView(Long tenantId, String tenantCode, String tenantName,
                                  Long adminUserId, String adminUsername, String adminNickname) {
}
