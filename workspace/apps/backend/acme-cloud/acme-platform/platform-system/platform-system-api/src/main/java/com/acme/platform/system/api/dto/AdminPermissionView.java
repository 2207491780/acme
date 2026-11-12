package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.PermissionType;

import java.time.Instant;

/**
 * 权限点视图（平台级目录，全租户统一）。
 */
public record AdminPermissionView(Long id, String permissionCode, String permissionName,
                                  PermissionType permissionType, String httpMethod, String httpPath,
                                  EnableStatus status, Instant createdAt, Long version) {
}
