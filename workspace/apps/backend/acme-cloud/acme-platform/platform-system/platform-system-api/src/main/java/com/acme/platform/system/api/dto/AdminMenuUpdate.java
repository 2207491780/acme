package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 菜单节点修改请求（menuType / parentId 不可改，保持树稳定；version=乐观锁版本）。
 */
public record AdminMenuUpdate(@NotBlank @Size(min = 1, max = 64) String menuName,
                              @Size(max = 255) String path,
                              @Size(max = 255) String component,
                              @Size(max = 64) String icon,
                              @NotNull @Min(0) @Max(9999) Integer sortOrder,
                              Boolean visible,
                              @Size(max = 128) String permissionCode,
                              @NotNull Long version) {
}
