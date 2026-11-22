package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开通模板创建请求（REQ-20261122-233426 确认②④）：code 必填 ≤64 全局唯一（重码 →
 * TENANT_TEMPLATE_DUPLICATE）；name 必填 ≤128；content 必填（保留前缀/空角色集/未知配置键 →
 * TENANT_TEMPLATE_CONTENT_INVALID，校验在用例层）；defaultPackageId 可空=null 不设默认套餐
 * （非空须为既有套餐 → 否则 TENANT_PACKAGE_NOT_FOUND）。
 */
public record AdminTenantInitTemplateCreate(@NotBlank @Size(max = 64) String code,
                                            @NotBlank @Size(max = 128) String name,
                                            @Size(max = 255) String remark,
                                            @NotNull AdminTenantInitTemplateContent content,
                                            Long defaultPackageId) {
}
