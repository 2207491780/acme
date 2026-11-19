package com.acme.platform.auth.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminPasswordSetRequest;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 凭据设置 / 重置用例（FR-005，架构设计 4.6）：
 * 权限点 auth:credential:manage（经 system 判定，403 PERMISSION_DENIED）；upsert 凭据（BCrypt cost 10）
 * + 撤销该用户全部活跃会话；目标 userId 不做存在性校验（upsert 语义——system 侧用户不存在则
 * 永远无人可登录该凭据，无安全影响）。
 * 撤销失败 → 显式失败响应（CommonError.INTERNAL_ERROR；FR-004 不得「响应成功但令牌仍可用」）。
 */
@Service
public class ResetPasswordUseCase {

    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;

    /** 新建凭据时归属标注缺省（操作者租户缺失时回退，7.3.1 tenant_id 归属标注列）。 */
    static final String FALLBACK_TENANT = "system";

    private final AuthorizationGateway authorizationGateway;
    private final CredentialRecorder credentialRecorder;
    private final SessionRedis sessionRedis;
    private final BCryptPasswordEncoder passwordEncoder;

    public ResetPasswordUseCase(AuthorizationGateway authorizationGateway,
                                CredentialRecorder credentialRecorder,
                                SessionRedis sessionRedis,
                                BCryptPasswordEncoder passwordEncoder) {
        this.authorizationGateway = authorizationGateway;
        this.credentialRecorder = credentialRecorder;
        this.sessionRedis = sessionRedis;
        this.passwordEncoder = passwordEncoder;
    }

    /** 设置 / 重置密码并撤销该用户全部活跃会话。 */
    public void resetPassword(Long userId, AdminPasswordSetRequest request) {
        authorizationGateway.requireCredentialManage();
        String password = request.password();
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new BizException(AuthError.PASSWORD_POLICY_VIOLATED);
        }
        String hash = passwordEncoder.encode(password);
        // 事务内仅本服务库写（M-05）；会话撤销清单随事务返回、Redis 写在事务外
        List<SessionRecorder.RevocableToken> revocables =
                credentialRecorder.resetCredential(userId, hash, currentTenantOrFallback());
        // 会话已置 KICKED 而撤销失败：显式失败响应，不出现「成功但可用」（FR-004 异常分支）
        SessionRevocations.allOrFail(sessionRedis, revocables);
    }

    private static String currentTenantOrFallback() {
        TenantContext tenant = TenantContext.get();
        return tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()
                ? FALLBACK_TENANT : tenant.tenantId();
    }
}
