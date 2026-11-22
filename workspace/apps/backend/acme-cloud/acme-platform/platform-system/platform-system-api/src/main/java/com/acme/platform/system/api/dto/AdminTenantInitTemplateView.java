package com.acme.platform.system.api.dto;

import java.time.Instant;

/**
 * 开通模板视图（REQ-20261122-233426 分页 / 创建 / 修改响应；content 结构化透传——
 * rolePresets/configEntries 供前端结构化编辑，确认⑦模板 Tab）。
 *
 * @param id               模板 ID（wire 字符串化雪花 ID）
 * @param code             模板编码（全局唯一）
 * @param name             模板名称
 * @param remark           备注（可空）
 * @param content          模板内容（快照形状见 {@link AdminTenantInitTemplateContent}）
 * @param defaultPackageId 默认套餐 ID（可空=null 不设默认套餐）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @param version          乐观锁版本（wire 字符串化）
 */
public record AdminTenantInitTemplateView(Long id, String code, String name, String remark,
                                          AdminTenantInitTemplateContent content, Long defaultPackageId,
                                          Instant createdAt, Instant updatedAt, Long version) {
}
