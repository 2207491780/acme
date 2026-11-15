package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 岗位创建请求（REQ-20261114-120104：名称/编码/状态；status 缺省 ENABLED）。
 * 请求体双键兼容（确认记录⑪）：positionCode/code、positionName/name 等价绑定——wire 主形状以
 * code/name 为准，别名组件仅在反序列化侧吸收兼容键（请求 DTO 不作为出参，不上 wire）。
 */
public record AdminPositionCreate(@NotBlank @Size(min = 1, max = 64) String code,
                                  @NotBlank @Size(min = 1, max = 64) String name,
                                  EnableStatus status,
                                  String positionCode,
                                  String positionName) {

    public AdminPositionCreate {
        if (code == null || code.isBlank()) {
            code = positionCode;
        }
        if (name == null || name.isBlank()) {
            name = positionName;
        }
    }

    /** 兼容旧参构造（既有调用面：用例测试 / 集成测试 / 内部装配）。 */
    public AdminPositionCreate(String code, String name, EnableStatus status) {
        this(code, name, status, null, null);
    }
}
