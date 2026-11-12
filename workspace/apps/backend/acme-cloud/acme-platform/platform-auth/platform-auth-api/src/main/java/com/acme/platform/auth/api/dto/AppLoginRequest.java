package com.acme.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * C 端验证码登录请求（11.4：验证码登录；手机号格式显式校验）。
 */
public record AppLoginRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String captcha) {
}
