package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开通模板修改请求（REQ-20261122-233426，校验同创建；content 全量替换——快照语义：
 * 模板变更不影响已开通租户，确认④）。version=乐观锁版本（可空：携带时前置校验
 * CONCURRENCY_CONFLICT；不携带时跳过前置校验，由仓储 save 的 DB 级乐观锁兜底
 * FR-010——version 作更新 WHERE 条件，0 行=STALE_WRITE）。
 */
public record AdminTenantInitTemplateUpdate(@NotBlank @Size(max = 64) String code,
                                            @NotBlank @Size(max = 128) String name,
                                            @Size(max = 255) String remark,
                                            @NotNull AdminTenantInitTemplateContent content,
                                            Long defaultPackageId,
                                            Long version) {
}
