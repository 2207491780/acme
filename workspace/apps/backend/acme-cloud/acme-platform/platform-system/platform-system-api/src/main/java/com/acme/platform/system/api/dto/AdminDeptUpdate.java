package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 部门修改请求（REQ-20261114-120104：parentId null=不调整，0=移为根部门；version=乐观锁版本）。
 * 请求体双键兼容（确认记录⑪）：deptName/name、sort/sortOrder 等价绑定——wire 主形状以
 * name/sortOrder 为准，别名组件仅在反序列化侧吸收兼容键（请求 DTO 不作为出参，不上 wire）。
 */
public record AdminDeptUpdate(@NotBlank @Size(min = 1, max = 64) String name,
                              Long parentId,
                              @Min(0) @Max(9999) Integer sortOrder,
                              EnableStatus status,
                              @NotNull Long version,
                              String deptName,
                              Integer sort) {

    public AdminDeptUpdate {
        if (name == null || name.isBlank()) {
            name = deptName;
        }
        if (sortOrder == null) {
            sortOrder = sort;
        }
    }

    /** 兼容旧参构造（既有调用面：用例测试 / 集成测试 / 内部装配）。 */
    public AdminDeptUpdate(String name, Long parentId, Integer sortOrder, EnableStatus status, Long version) {
        this(name, parentId, sortOrder, status, version, null, null);
    }
}
