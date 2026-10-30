package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.application.AuthProfileUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录认证画像内部接口（架构设计 3.3；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1）；404 错误信封由全局异常映射产出。
 */
@RestController
public class AuthProfileController {

    private final AuthProfileUseCase authProfileUseCase;

    public AuthProfileController(AuthProfileUseCase authProfileUseCase) {
        this.authProfileUseCase = authProfileUseCase;
    }

    @GetMapping("/internal/v1/auth-profiles")
    public Result<AuthProfileView> findByUsername(@RequestParam("username") String username) {
        return Result.ok(authProfileUseCase.findByUsername(username));
    }
}
