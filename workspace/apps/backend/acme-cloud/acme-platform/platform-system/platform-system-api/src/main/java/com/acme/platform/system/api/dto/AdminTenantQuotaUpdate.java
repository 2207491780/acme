package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 租户配额覆盖请求（PUT /admin/v1/system/tenants/{id}/quota，REQ-20261122-150802 确认③⑦）。
 *
 * @param quotaStorageBytes 存储配额覆盖字节：非空=覆盖（0=不限）；null=清除继承（回落套餐默认 /
 *                          平台缺省，覆盖行保留仅置空——确认③「NULL=继承套餐」）
 */
public record AdminTenantQuotaUpdate(@PositiveOrZero Long quotaStorageBytes) {
}
