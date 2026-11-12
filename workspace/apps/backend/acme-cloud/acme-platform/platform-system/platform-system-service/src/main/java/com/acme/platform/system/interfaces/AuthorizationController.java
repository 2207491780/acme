package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.application.AuthorizationUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间权限判定内部接口（FR-008 跨域执行面；调用方角色集取内部令牌 claims）。
 */
@RestController
public class AuthorizationController {

    private final AuthorizationUseCase authorizationUseCase;

    public AuthorizationController(AuthorizationUseCase authorizationUseCase) {
        this.authorizationUseCase = authorizationUseCase;
    }

    @GetMapping("/internal/v1/authorization/permissions/{code}")
    public Result<Boolean> hasPermission(@PathVariable("code") String code) {
        return Result.ok(authorizationUseCase.hasPermission(code));
    }
}
