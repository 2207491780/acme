package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 角色创建请求（roleCode 租户内唯一，7.2.3；parentId 缺省为根角色 0，父角色须存在）。
 */
public record AdminRoleCreate(Long parentId,
                              @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String roleCode,
                              @NotBlank @Size(min = 1, max = 64) String roleName,
                              @Size(max = 255) String remark) {
}
