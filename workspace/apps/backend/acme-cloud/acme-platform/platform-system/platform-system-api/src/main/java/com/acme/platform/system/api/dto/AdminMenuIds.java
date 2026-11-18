package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色菜单绑定整体替换请求（菜单级授权；服务端自动补全父链保证树完整，10.3.1 幂等）。
 */
public record AdminMenuIds(@NotNull List<Long> menuIds) {

    public AdminMenuIds {
        menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
    }
}
