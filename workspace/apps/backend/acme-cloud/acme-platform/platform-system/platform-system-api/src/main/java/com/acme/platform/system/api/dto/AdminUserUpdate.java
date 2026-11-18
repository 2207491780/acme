package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用户基本信息修改请求（version=乐观锁版本，AuditBase @Version）；
 * REQ-20261114-120104 增补 deptId / positionId（null=不调整，非空时校验存在）。
 */
public record AdminUserUpdate(@NotBlank @Size(min = 1, max = 64) String nickname,
                              @Size(max = 128) String email,
                              @Size(max = 32) String phone,
                              @NotNull Long version,
                              Long deptId,
                              Long positionId) {

    /** 兼容旧参构造（无部门/岗位字段调用面：既有测试与内部装配）。 */
    public AdminUserUpdate(String nickname, String email, String phone, Long version) {
        this(nickname, email, phone, version, null, null);
    }
}
