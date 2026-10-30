package com.acme.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * C 端验证码发送请求（频控见 AuthErrorCodes.CAPTCHA_SEND_LIMITED）。
 */
public record AppCaptchaRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone) {
}
