package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 租户修改请求（tenantCode 不可改；version=乐观锁版本；无删除能力——停用代替，FR-007）。
 */
public record AdminTenantUpdate(@NotBlank @Size(min = 1, max = 128) String tenantName,
                                @Size(max = 255) String remark,
                                @NotNull Long version) {
}
