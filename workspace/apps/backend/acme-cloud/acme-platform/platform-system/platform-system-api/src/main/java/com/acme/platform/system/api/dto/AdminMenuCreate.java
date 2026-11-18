package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.MenuType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 菜单节点创建请求（parentId 缺省为根 0；父节点须存在）。
 */
public record AdminMenuCreate(Long parentId,
                              @NotBlank @Size(min = 1, max = 64) String menuName,
                              @NotNull MenuType menuType,
                              @Size(max = 255) String path,
                              @Size(max = 255) String component,
                              @Size(max = 64) String icon,
                              @NotNull @Min(0) @Max(9999) Integer sortOrder,
                              Boolean visible,
                              @Size(max = 128) String permissionCode) {
}
