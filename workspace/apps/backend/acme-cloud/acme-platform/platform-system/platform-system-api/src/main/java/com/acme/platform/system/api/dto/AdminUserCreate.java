package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 用户创建请求（FR-006/FR-007）：不含密码——初始密码经 platform-auth 凭据端点两步设置（架构设计 4.6）；
 * REQ-20261114-120104 增补 deptId / positionId（可空，非空时校验存在）。
 */
public record AdminUserCreate(@NotBlank @Size(min = 3, max = 64) String username,
                              @NotBlank @Size(min = 1, max = 64) String nickname,
                              @Size(max = 128) String email,
                              @Size(max = 32) String phone,
                              List<Long> roleIds,
                              Long deptId,
                              Long positionId) {

    public AdminUserCreate {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
    }

    /** 兼容旧参构造（无部门/岗位字段调用面：既有测试与内部装配）。 */
    public AdminUserCreate(String username, String nickname, String email, String phone, List<Long> roleIds) {
        this(username, nickname, email, phone, roleIds, null, null);
    }
}
