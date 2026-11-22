package com.acme.platform.system.api.dto;

/**
 * 租户用量视图（GET /admin/v1/system/tenants/{id}/usage，REQ-20261122-150802 确认④⑦）：
 * 运营用量实时旁路聚合（数据量小先实时，定时聚合留后续）。
 *
 * @param tenantId          租户 ID（wire 字符串化雪花 ID）
 * @param tenantCode        租户代码
 * @param storageBytes      存储用量字节（sys_file 未删行 size 总和旁路聚合，@IgnoreTenant 口径）
 * @param userCount         租户内用户数（sys_user 未删行计数旁路聚合）
 * @param quotaStorageBytes 解析后有效存储配额字节（三级覆盖链租户覆盖→套餐→平台缺省；0=不限）
 * @param quotaSource       配额来源层级（TENANT=租户覆盖 / PACKAGE=套餐默认 / PLATFORM=平台缺省）
 */
public record AdminTenantUsageView(Long tenantId, String tenantCode, long storageBytes, long userCount,
                                   long quotaStorageBytes, String quotaSource) {
}
