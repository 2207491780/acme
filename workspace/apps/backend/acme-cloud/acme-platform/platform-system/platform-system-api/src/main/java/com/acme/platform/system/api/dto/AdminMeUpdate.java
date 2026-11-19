package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 本人资料修改请求（REQ-20261119-082531 确认记录①，PUT /admin/v1/system/me）：
 * nickname 必填 ≤32；email 可空且须 email 格式（@Email——null/空串放行即「可空」语义）；
 * phone 可空且须 1\d{10}；
 * avatarUrl 可空（头像地址——由 platform-infra 头像上传端点产出后前端回填，TL 决策①），
 * 值域 http(s) 前缀或相对地址、长度 ≤512（格式校验在 MeUseCase，PROFILE_INVALID 承载）。
 * 与 AdminUserUpdate（管理员面，version 乐观锁）不同：本人自助面无 version 字段，
 * 按当前行版本直更（上下文 userId 即本人）。
 */
public record AdminMeUpdate(@NotBlank @Size(max = 32) String nickname,
                            @Email @Size(max = 128) String email,
                            @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号须为 1 开头的 11 位数字")
                            @Size(max = 32) String phone,
                            @Size(max = 512) String avatarUrl) {
}
