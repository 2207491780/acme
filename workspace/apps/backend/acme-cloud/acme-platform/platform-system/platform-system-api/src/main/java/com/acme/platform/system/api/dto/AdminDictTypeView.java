package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;

/**
 * 字典类型视图（租户内唯一 dictCode）。
 */
public record AdminDictTypeView(Long id, String dictCode, String dictName, EnableStatus status,
                                String remark, Instant createdAt, Long version) {
}
