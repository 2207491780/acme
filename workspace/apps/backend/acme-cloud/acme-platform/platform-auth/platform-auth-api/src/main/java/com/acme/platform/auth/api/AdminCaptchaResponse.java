package com.acme.platform.auth.api;

/**
 * B 端图形验证码响应（login-guard-captcha）：
 * captchaId 登录时原样回传；imageBase64Data 为 {@code data:image/png;base64,...} data-URI，前端直接渲染。
 */
public record AdminCaptchaResponse(String captchaId, String imageBase64Data) {
}
