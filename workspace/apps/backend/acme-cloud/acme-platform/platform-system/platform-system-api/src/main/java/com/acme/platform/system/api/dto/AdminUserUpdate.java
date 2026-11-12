package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用户基本信息修改请求（version=乐观锁版本，AuditBase @Version）。
 */
public record AdminUserUpdate(@NotBlank @Size(min = 1, max = 64) String nickname,
                              @Size(max = 128) String email,
                              @Size(max = 32) String phone,
                              @NotNull Long version) {
}
