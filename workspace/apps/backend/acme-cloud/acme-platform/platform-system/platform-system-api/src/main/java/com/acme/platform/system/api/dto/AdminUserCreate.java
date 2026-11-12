package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 用户创建请求（FR-006/FR-007）：不含密码——初始密码经 platform-auth 凭据端点两步设置（架构设计 4.6）。
 */
public record AdminUserCreate(@NotBlank @Size(min = 3, max = 64) String username,
                              @NotBlank @Size(min = 1, max = 64) String nickname,
                              @Size(max = 128) String email,
                              @Size(max = 32) String phone,
                              List<Long> roleIds) {

    public AdminUserCreate {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
    }
}
