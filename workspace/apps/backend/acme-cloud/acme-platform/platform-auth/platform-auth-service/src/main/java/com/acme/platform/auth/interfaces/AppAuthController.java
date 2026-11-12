package com.acme.platform.auth.interfaces;

import com.acme.platform.auth.api.dto.AppCaptchaRequest;
import com.acme.platform.auth.api.dto.AppLoginRequest;
import com.acme.platform.auth.api.dto.AppRefreshRequest;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import com.acme.platform.auth.application.CaptchaUseCase;
import com.acme.platform.auth.application.ConsumerLoginUseCase;
import com.acme.platform.auth.application.LogoutUseCase;
import com.acme.platform.auth.application.RefreshTokenUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端认证接口（consumer-auth-refresh-dataperm，11.4：验证码登录 / 令牌+刷新 / 多端并存）：
 * 路径含端别前缀 /app/v1/auth/**（网关 C 端独立策略）；验证码 / 登录 / 刷新为公开豁免端点
 * （网关 + 服务侧 acme.security.public-paths 双侧一致最小集，FR-012）；登出需认证。
 */
@RestController
@RequestMapping("/app/v1/auth")
public class AppAuthController {

    private final CaptchaUseCase captchaUseCase;
    private final ConsumerLoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshUseCase;
    private final LogoutUseCase logoutUseCase;

    public AppAuthController(CaptchaUseCase captchaUseCase, ConsumerLoginUseCase loginUseCase,
                             RefreshTokenUseCase refreshUseCase, LogoutUseCase logoutUseCase) {
        this.captchaUseCase = captchaUseCase;
        this.loginUseCase = loginUseCase;
        this.refreshUseCase = refreshUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    /** 发送验证码（公开端点；60s/手机号频控）。 */
    @PostMapping("/captcha")
    public void captcha(@Valid @RequestBody AppCaptchaRequest request) {
        captchaUseCase.send(request.phone());
    }

    /** 验证码登录（公开端点；首登自动注册；签发双令牌）。 */
    @PostMapping("/tokens")
    public AppTokenIssued login(@Valid @RequestBody AppLoginRequest request) {
        return loginUseCase.login(request.phone(), request.captcha());
    }

    /** 刷新轮换（公开端点自证：一次性轮换，旧对即用即废）。 */
    @PostMapping("/tokens/refresh")
    public AppTokenIssued refresh(@Valid @RequestBody AppRefreshRequest request) {
        return refreshUseCase.refresh(request.refreshToken());
    }

    /** 登出（需认证；双令牌一并撤销，幂等成功 10.3.1）。 */
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
