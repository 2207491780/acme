package com.acme.platform.auth.interfaces;

import com.acme.platform.auth.api.AdminPasswordSetRequest;
import com.acme.platform.auth.application.ResetPasswordUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 凭据管理接口（FR-005 输入「凭证设置 / 变更」，架构设计 4.6）：
 * upsert 凭据并撤销该用户全部活跃会话；权限点 auth:credential:manage（经 system 判定，越权 403）。
 */
@RestController
@RequestMapping("/admin/v1/auth/credentials")
public class CredentialController {

    private final ResetPasswordUseCase resetPasswordUseCase;

    public CredentialController(ResetPasswordUseCase resetPasswordUseCase) {
        this.resetPasswordUseCase = resetPasswordUseCase;
    }

    /** 设置 / 重置用户密码。 */
    @PutMapping("/users/{userId}/password")
    public void resetPassword(@PathVariable Long userId, @Valid @RequestBody AdminPasswordSetRequest request) {
        resetPasswordUseCase.resetPassword(userId, request);
    }
}
