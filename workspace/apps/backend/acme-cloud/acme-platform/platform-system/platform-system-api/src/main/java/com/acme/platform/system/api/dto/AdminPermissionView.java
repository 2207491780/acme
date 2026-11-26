package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.PermissionType;

import java.time.Instant;

/**
 * 权限点视图（平台级目录，全租户统一）。
 * REQ-20261125-230807 增补 service（V36 迁移维护列：split_part(http_path,'/',4) 主、
 * permission_code 首段兜底、content→contents 归一；Create/Update DTO 不接收、列表筛选不加，
 * 与「flyway 维护目录」口径一致）。
 */
public record AdminPermissionView(Long id, String permissionCode, String permissionName,
                                  PermissionType permissionType, String httpMethod, String httpPath,
                                  String service, EnableStatus status, Instant createdAt, Long version) {
}
