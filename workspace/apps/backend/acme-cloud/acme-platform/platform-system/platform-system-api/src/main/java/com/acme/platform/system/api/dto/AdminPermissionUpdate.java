package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 权限点修改请求（编码不可改；version=乐观锁版本）。
 */
public record AdminPermissionUpdate(@NotBlank @Size(min = 1, max = 64) String permissionName,
                                    @Size(max = 16) String httpMethod,
                                    @Size(max = 255) String httpPath,
                                    @NotNull Long version) {
}
