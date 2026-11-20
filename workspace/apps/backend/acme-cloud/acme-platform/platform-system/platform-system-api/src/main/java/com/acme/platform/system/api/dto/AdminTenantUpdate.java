package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 租户修改请求（tenantCode 不可改；version=乐观锁版本；无删除能力——停用代替，FR-007）。
 * REQ-20261117-025200 增补联系人 / 有效期三字段（均可空；expiresAt ISO-8601 字符串由 Jackson 反序列化，
 * null=长期有效；不校验过去时间——存量允许，登录时拒绝即可，契约 §API 租户 CRUD 扩展）。
 * REQ-20261121-022910 增补 packageId（null=解除套餐绑定（不受限）；非空须为既有套餐，否则
 * TENANT_PACKAGE_NOT_FOUND，契约 §API 租户更新扩展）。
 */
public record AdminTenantUpdate(@NotBlank @Size(min = 1, max = 128) String tenantName,
                                @Size(max = 255) String remark,
                                @Size(max = 64) String contactName,
                                @Size(max = 32) String contactPhone,
                                Instant expiresAt,
                                @NotNull Long version,
                                Long packageId) {
}
