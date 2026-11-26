package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.DictKind;
import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;

/**
 * 字典类型视图（租户内唯一 dictCode；REQ-20261125-230807 增补 dictKind=FLAT|TREE）。
 */
public record AdminDictTypeView(Long id, String dictCode, String dictName, DictKind dictKind,
                                EnableStatus status, String remark, Instant createdAt, Long version) {
}
