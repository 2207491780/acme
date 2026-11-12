package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;

/**
 * 租户视图（跨租户平台管理场景，12.4.2）。
 */
public record AdminTenantView(Long id, String tenantCode, String tenantName, EnableStatus status,
                              String remark, Instant createdAt, Long version) {
}
