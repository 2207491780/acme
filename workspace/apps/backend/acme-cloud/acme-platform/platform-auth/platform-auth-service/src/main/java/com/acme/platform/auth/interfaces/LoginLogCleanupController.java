package com.acme.platform.auth.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.auth.application.LoginLogRetentionCleanupUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录日志保留期清理内部端点（REQ-20261114-120105 TL 裁决 C 方案，确认记录⑦）：
 * {@code POST /internal/v1/login-logs/purge-cleanup}——platform-infra 任务体经 Feign 调用，
 * 幂等可重复调用，返回标准 Result 信封（data=本次物理清理条数）。不经网关对外，openapi 不登记。
 */
@RestController
public class LoginLogCleanupController {

    private final LoginLogRetentionCleanupUseCase cleanupUseCase;

    public LoginLogCleanupController(LoginLogRetentionCleanupUseCase cleanupUseCase) {
        this.cleanupUseCase = cleanupUseCase;
    }

    /** 软删标记 + 物理清理（幂等）；data=本次物理清理条数。 */
    @PostMapping("/internal/v1/login-logs/purge-cleanup")
    public Result<Integer> purgeCleanup() {
        return Result.ok(cleanupUseCase.cleanupExpired());
    }
}
