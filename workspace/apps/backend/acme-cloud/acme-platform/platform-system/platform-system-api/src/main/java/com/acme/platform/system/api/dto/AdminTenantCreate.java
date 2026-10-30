package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 租户创建请求（tenantCode 全局唯一且不可改，为租户标识取值来源）。
 */
public record AdminTenantCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String tenantCode,
                                @NotBlank @Size(min = 1, max = 128) String tenantName,
                                @Size(max = 255) String remark) {
}
