package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 套餐创建请求（REQ-20261121-022910；capabilities 扩展 REQ-20261121-054613 确认④）：
 * packageName 必填 ≤128 全局唯一（重名 → TENANT_PACKAGE_DUPLICATE）；remark 可空 ≤255；
 * menuIds 可空 = 空套餐（含不存在或已删菜单 → TENANT_PACKAGE_MENU_INVALID，存在性校验在用例层）；
 * capabilities 可选 = 缺省空集（与 menuIds 同口径全量替换；含未知能力码 → TENANT_CAPABILITY_INVALID，
 * 校验在用例层）。
 */
public record AdminTenantPackageCreate(@NotBlank @Size(max = 128) String packageName,
                                       @Size(max = 255) String remark,
                                       List<Long> menuIds,
                                       List<String> capabilities) {

    public AdminTenantPackageCreate {
        menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
