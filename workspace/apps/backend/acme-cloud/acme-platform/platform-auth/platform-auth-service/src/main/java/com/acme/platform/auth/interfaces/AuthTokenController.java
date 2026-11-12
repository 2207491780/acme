package com.acme.platform.auth.interfaces;

import com.acme.platform.auth.api.AdminCaptchaResponse;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.application.AdminRenewUseCase;
import com.acme.platform.auth.application.ImageCaptchaUseCase;
import com.acme.platform.auth.application.LoginUseCase;
import com.acme.platform.auth.application.LogoutUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（FR-001/002/004；路径含端别前缀——网关不 StripPrefix，架构设计 6.1 取舍 #8）：
 * 图形验证码与登录为公开豁免端点（网关 + 服务侧 acme.security.public-paths 双侧一致最小集，FR-012）；
 * 登出需认证（网关验签 + 撤销检查后转发，Authorization 头原样透传——网关仅剥离 X-Acme-* 身份头）。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthTokenController {

    private final LoginUseCase loginUseCase;
    private final LogoutUseCase logoutUseCase;
    private final ImageCaptchaUseCase imageCaptchaUseCase;
    private final AdminRenewUseCase adminRenewUseCase;

    public AuthTokenController(LoginUseCase loginUseCase, LogoutUseCase logoutUseCase,
                               ImageCaptchaUseCase imageCaptchaUseCase, AdminRenewUseCase adminRenewUseCase) {
        this.loginUseCase = loginUseCase;
        this.logoutUseCase = logoutUseCase;
        this.imageCaptchaUseCase = imageCaptchaUseCase;
        this.adminRenewUseCase = adminRenewUseCase;
    }

    /** 图形验证码（公开端点；Hutool LineCaptcha 4 位，captchaId 一次性消费，防登录爆破）。 */
    @GetMapping("/captcha")
    public AdminCaptchaResponse captcha() {
        return imageCaptchaUseCase.generate();
    }

    /** 登录并签发 B 端访问令牌（公开端点；前置图形验证码一次性校验 + 租户一致性校验）。 */
    @PostMapping("/tokens")
    public AdminTokenIssued login(@Valid @RequestBody AdminLoginRequest request) {
        return loginUseCase.login(request.tenantCode(), request.username(), request.password(),
                request.captchaId(), request.captchaCode());
    }

    /** 自续约（滑动会话，login-renew）：携带当前 Bearer 访问令牌换取同 claims 新令牌；
     *  需认证端点（不进公开豁免）——令牌过期即 401 转登录页，前端在过期前 2 分钟自动调用。 */
    @PostMapping("/tokens/refresh")
    public AdminTokenIssued renew(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return adminRenewUseCase.renew(stripBearer(authorization));
    }

    /** 登出（当前会话即刻失效；已失效令牌幂等成功，10.3.1）。 */
    @DeleteMapping("/tokens/current")
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        logoutUseCase.logout(stripBearer(authorization));
    }

    /** Bearer 前缀剥离（网关 AuthFilter 同型口径）。 */
    private static String stripBearer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }
}
