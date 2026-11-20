package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 套餐修改请求（REQ-20261121-022910，校验同创建）：packageName 必填 ≤128（撞既有名 →
 * TENANT_PACKAGE_DUPLICATE）；menuIds 全量替换套餐菜单绑定（含不存在或已删菜单 →
 * TENANT_PACKAGE_MENU_INVALID）；version=乐观锁版本（框架 update 惯例，FR-010）。
 */
public record AdminTenantPackageUpdate(@NotBlank @Size(max = 128) String packageName,
                                       @Size(max = 255) String remark,
                                       List<Long> menuIds,
                                       @NotNull Long version) {

    public AdminTenantPackageUpdate {
        menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
    }
}
