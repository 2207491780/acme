package com.acme.platform.auth.interfaces;

import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.application.LoginUseCase;
import com.acme.platform.auth.application.LogoutUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（FR-001/002/004；路径含端别前缀——网关不 StripPrefix，架构设计 6.1 取舍 #8）：
 * 登录为公开豁免端点（网关 + 服务侧 acme.security.public-paths 双侧一致最小集，FR-012）；
 * 登出需认证（网关验签 + 撤销检查后转发，Authorization 头原样透传——网关仅剥离 X-Acme-* 身份头）。
 */
@RestController
@RequestMapping("/admin/v1/auth/tokens")
public class AuthTokenController {

    private final LoginUseCase loginUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthTokenController(LoginUseCase loginUseCase, LogoutUseCase logoutUseCase) {
        this.loginUseCase = loginUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    /** 登录并签发 B 端访问令牌（公开端点）。 */
    @PostMapping
    public AdminTokenIssued login(@Valid @RequestBody AdminLoginRequest request) {
        return loginUseCase.login(request.username(), request.password());
    }

    /** 登出（当前会话即刻失效；已失效令牌幂等成功，10.3.1）。 */
    @DeleteMapping("/current")
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
