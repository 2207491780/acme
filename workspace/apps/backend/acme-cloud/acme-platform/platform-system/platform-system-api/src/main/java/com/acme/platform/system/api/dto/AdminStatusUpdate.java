package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 启停状态修改请求（用户 / 角色 / 租户共用，FR-007 按能力语义的启用停用）。
 */
public record AdminStatusUpdate(@NotNull EnableStatus status) {
}
