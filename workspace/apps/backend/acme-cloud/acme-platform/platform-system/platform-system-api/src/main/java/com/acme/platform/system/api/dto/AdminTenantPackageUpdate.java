package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 套餐修改请求（REQ-20261121-022910，校验同创建；capabilities 扩展 REQ-20261121-054613 确认④）：
 * packageName 必填 ≤128（撞既有名 → TENANT_PACKAGE_DUPLICATE）；menuIds 全量替换套餐菜单绑定
 * （含不存在或已删菜单 → TENANT_PACKAGE_MENU_INVALID）；capabilities 全量替换套餐能力绑定
 * （缺省=空集；含未知能力码 → TENANT_CAPABILITY_INVALID）；version=乐观锁版本（框架 update 惯例，FR-010）。
 */
public record AdminTenantPackageUpdate(@NotBlank @Size(max = 128) String packageName,
                                       @Size(max = 255) String remark,
                                       List<Long> menuIds,
                                       List<String> capabilities,
                                       @NotNull Long version) {

    public AdminTenantPackageUpdate {
        menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
