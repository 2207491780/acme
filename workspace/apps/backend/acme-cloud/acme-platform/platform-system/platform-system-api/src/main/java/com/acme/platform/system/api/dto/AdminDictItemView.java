package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

/**
 * 字典项视图（同类型下 itemValue 唯一）。
 */
public record AdminDictItemView(Long id, Long dictTypeId, String itemLabel, String itemValue,
                                Integer sortOrder, EnableStatus status, Long version) {
}
