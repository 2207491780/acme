package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;

/**
 * 租户视图（跨租户平台管理场景，12.4.2）。
 * REQ-20261117-025200 增补联系人 / 有效期三字段（可空语义：null=未配置 / 长期有效）。
 *
 * @param id           租户 ID（wire 字符串化雪花 ID）
 * @param tenantCode   租户代码（全局唯一，不可改）
 * @param tenantName   租户名称
 * @param status       启停状态
 * @param remark       备注（可空）
 * @param contactName  联系人姓名（可空；REQ-20261117-025200）
 * @param contactPhone 联系人电话（可空；REQ-20261117-025200）
 * @param expiresAt    租户有效期（可空=长期；过期租户登录拒绝与下拉排除，确认记录③；ISO-8601 字符串）
 * @param createdAt    创建时间
 * @param version      乐观锁版本（wire 字符串化）
 */
public record AdminTenantView(Long id, String tenantCode, String tenantName, EnableStatus status,
                              String remark, String contactName, String contactPhone, Instant expiresAt,
                              Instant createdAt, Long version) {
}
