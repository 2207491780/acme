package com.acme.platform.system.api;

/**
 * 租户配额解析视图（/internal/v1/tenants/quota 契约，REQ-20261122-150802 确认③）：
 * platform-infra FileUseCase.checkQuota 收编数据源，以服务身份主体调用（Q-D08，同 TenantClient 口径）。
 *
 * @param quotaStorageBytes 解析后有效存储配额字节（三级覆盖链租户覆盖→套餐→平台缺省；0=不限）
 * @param quotaSource       配额来源层级（TENANT=租户覆盖 / PACKAGE=套餐默认 / PLATFORM=平台缺省）
 */
public record TenantQuotaView(long quotaStorageBytes, String quotaSource) {
}
