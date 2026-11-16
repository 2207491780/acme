package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 租户级系统配置修改请求（REQ-20261116-230354；upsert 语义，version=乐观锁版本）。
 * lockIdleTimeoutMs 取值集 {0,60000,300000,900000} 在用例层校验（业务失败码≠0）；
 * captchaEnabled 为 boolean 开关（REQ-20261117-011514），无取值集校验。
 */
public record AdminSystemConfigUpdate(@NotNull Boolean watermarkEnabled,
                                      @NotNull Boolean captchaEnabled,
                                      @NotNull Integer lockIdleTimeoutMs,
                                      @NotNull Long version) {
}