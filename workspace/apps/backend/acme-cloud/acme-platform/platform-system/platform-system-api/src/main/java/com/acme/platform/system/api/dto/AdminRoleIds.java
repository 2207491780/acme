package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 用户角色绑定整体替换请求（10.3.1 幂等：重复提交同一集合结果一致）。
 */
public record AdminRoleIds(@NotNull List<Long> roleIds) {

    public AdminRoleIds {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
    }
}
