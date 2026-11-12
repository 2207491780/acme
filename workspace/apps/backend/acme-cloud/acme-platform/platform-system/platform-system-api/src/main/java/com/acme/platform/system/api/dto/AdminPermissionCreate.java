package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.PermissionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 权限点创建请求（编码全局唯一，7.2.4）。
 */
public record AdminPermissionCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9:-]*$") @Size(min = 3, max = 128) String permissionCode,
                                    @NotBlank @Size(min = 1, max = 64) String permissionName,
                                    @NotNull PermissionType permissionType,
                                    @Size(max = 16) String httpMethod,
                                    @Size(max = 255) String httpPath) {
}
