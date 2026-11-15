package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 角色数据范围设置请求（REQ-20261114-120104）：dataScope 取五档枚举名
 * （ALL/DEPT/DEPT_AND_CHILD/SELF/CUSTOM）；CUSTOM 须传非空 deptIds（否则 DATA_SCOPE_INVALID）。
 */
public record AdminRoleDataScope(@NotBlank @Size(max = 32) String dataScope,
                                 List<Long> deptIds) {

    public AdminRoleDataScope {
        deptIds = deptIds == null ? List.of() : List.copyOf(deptIds);
    }
}
