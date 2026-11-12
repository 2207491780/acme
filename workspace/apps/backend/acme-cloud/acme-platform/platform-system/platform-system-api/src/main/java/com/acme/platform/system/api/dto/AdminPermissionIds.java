package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限点绑定整体替换请求（接口级授权，10.3.1 幂等）。
 */
public record AdminPermissionIds(@NotNull List<Long> permissionIds) {

    public AdminPermissionIds {
        permissionIds = permissionIds == null ? List.of() : List.copyOf(permissionIds);
    }
}
