package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.TenantInternalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 登录用例（FR-001/FR-002，架构设计 4.2 主流程）：
 * 编排不开启整体事务——画像 Feign 查询置于凭据校验前、事务外（M-05；10.2.1），
 * DB 写经 SessionRecorder 独立事务；Redis 异常防爆破降级 fail-open（取舍 #7）。
 * REQ-20261117-011514：第 1 步验证码改按租户策略条件校验——按客户端声称的 tenantCode 实时查开关，
 * 关闭时跳过校验（C 端短信验证码路径不涉及）；策略查询失败 fail-closed 视为开启（AC-6）；
 * 声称租户与账号归属不匹配由既有第 4 步统一失败兜底，不产生绕过面（确认记录②）。
 * REQ-20261117-025200：第 4.5 步追加租户有效期校验（by-code 实查目录，服务身份）——
 * 非 ENABLED 或已过期统一 LOGIN_FAILED（确认记录③），目录查询失败 fail-closed（登录面从严）。
 */
@Service
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    private final AuthProfileGateway authProfileGateway;
    private final AuthCredentialRepository credentialRepository;
    private final LoginGuardRedis loginGuard;
    private final SessionRedis sessionRedis;
    private final SessionRecorder sessionRecorder;
    private final UserTokenIssuer tokenIssuer;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ImageCaptchaUseCase imageCaptchaUseCase;
    private final LoginLogRecorder loginLogRecorder;
    private final SystemConfigGateway systemConfigGateway;
    private final TenantDirectoryGateway tenantDirectoryGateway;

    public LoginUseCase(AuthProfileGateway authProfileGateway,
                        AuthCredentialRepository credentialRepository,
                        LoginGuardRedis loginGuard,
                        SessionRedis sessionRedis,
                        SessionRecorder sessionRecorder,
                        UserTokenIssuer tokenIssuer,
                        BCryptPasswordEncoder passwordEncoder,
                        ImageCaptchaUseCase imageCaptchaUseCase,
                        LoginLogRecorder loginLogRecorder,
                        SystemConfigGateway systemConfigGateway,
                        TenantDirectoryGateway tenantDirectoryGateway) {
        this.authProfileGateway = authProfileGateway;
        this.credentialRepository = credentialRepository;
        this.loginGuard = loginGuard;
        this.sessionRedis = sessionRedis;
        this.sessionRecorder = sessionRecorder;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.imageCaptchaUseCase = imageCaptchaUseCase;
        this.loginLogRecorder = loginLogRecorder;
        this.systemConfigGateway = systemConfigGateway;
        this.tenantDirectoryGateway = tenantDirectoryGateway;
    }

    /**
     * B 端登录（公开端点；多租户 + 按租户策略前置图形验证码；单会话互踢 Q-003；统一失败提示 FR-005）。
     * REQ-20261114-120105：成功/失败均记登录日志（异步落库，写失败不影响本路径）；
     * 会话信息（ip/lastActiveAt）随签发写入供在线用户展示。
     */
    public AdminTokenIssued login(String tenantCode, String username, String password,
                                  String captchaId, String captchaCode,
                                  String clientIp, String userAgent) {
        String normalized = username == null ? "" : username.trim();
        String normalizedTenant = tenantCode == null ? "" : tenantCode.trim();
        try {
            // 1. 图形验证码前置（REQ-20261117-011514：按租户策略条件校验，一次性消费防刷 login-guard-captcha；
            //    错误区分 INVALID/EXPIRED；策略查询失败 fail-closed 视为开启）
            if (captchaRequired(normalizedTenant)) {
                // 条件必填守卫（接口层已放宽为 @Size 上限校验）：任一字段空白按既有 INVALID 拒绝，
                // 不透传 verify（EXPIRED 语义归签发端真实缺失场景，REQ-20261117-011514）
                if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
                    throw new BizException(AuthError.CAPTCHA_INVALID);
                }
                imageCaptchaUseCase.verify(captchaId, captchaCode);
            }
            // 2. 锁定预检（Redis fail-open）
            if (loginGuard.isLocked(normalized)) {
                throw new BizException(AuthError.ACCOUNT_LOCKED);
            }
            // 3. 画像查询（事务外远程调用，Q-D06/Q-D08；REQ-20261125-001738 确认②按当前登录
            //    tenantCode + username 域内定位；404 → 统一失败，不泄露账号存在性差异）
            AuthProfileView profile = authProfileGateway.findByUsername(normalizedTenant, normalized);
            // 4. 租户一致性（多租户口径：SysUser.tenantId 存租户代码；不匹配按统一失败，不泄露归属差异 FR-005）
            if (!normalizedTenant.equals(profile.tenantId())) {
                throw new BizException(AuthError.LOGIN_FAILED);
            }
            // 4.5 租户有效期校验（REQ-20261117-025200 确认记录③，FR-005）：by-code 实查租户目录
            //     （服务身份，复用切换目标校验同一契约）；非 ENABLED 或已过期统一 LOGIN_FAILED 不泄露差异；
            //     目录查询失败 fail-closed 视为不可登录（登录面从严，WARN 记录）
            verifyTenantLoginable(normalizedTenant);
            // 5. 账号状态
            if (EnableStatus.DISABLED.name().equals(profile.status())) {
                throw new BizException(AuthError.ACCOUNT_DISABLED);
            }
            Long userId = Long.parseLong(profile.userId());
            // 6. 凭据校验（未设密码与密码错误统一失败，FR-005）
            AuthCredential credential = credentialRepository.findByUserId(userId);
            if (credential == null || !passwordEncoder.matches(password, credential.getPasswordHash())) {
                boolean locked = loginGuard.recordFailure(normalized);
                throw new BizException(locked ? AuthError.ACCOUNT_LOCKED : AuthError.LOGIN_FAILED);
            }
            loginGuard.clearFailures(normalized);
            // 7. 单会话互踢（DB 底账置 KICKED；撤销集写入 best-effort，Q-003）
            kickPreviousSessions(userId);
            // 8. 签发（jti=会话 tokenId；claims 契约见 4.1）+ 会话底账 + 单会话索引
            UserTokenIssuer.IssuedToken issued = tokenIssuer.issue(userId, profile.tenantId(), profile.roles());
            sessionRecorder.record(issued.tokenId(), userId, profile.tenantId(), UserTokenIssuer.CHANNEL_B,
                    issued.issuedAt(), issued.expiresAt());
            long ttl = Math.max(1, Duration.between(Instant.now(), issued.expiresAt()).toSeconds());
            sessionRedis.indexSession(userId, issued.tokenId(), ttl);
            // 9. 会话信息（在线用户 ip/lastActiveAt 富化；best-effort）+ 登录日志（成功，AC-1）
            sessionRedis.writeSessionInfo(issued.tokenId(), clientIp, issued.issuedAt(), ttl);
            loginLogRecorder.record(LoginLogRecorder.Entry.capture(normalized, UserTokenIssuer.CHANNEL_B,
                    clientIp, userAgent, true, null));
            return new AdminTokenIssued(issued.token(), "Bearer", issued.expiresAt(), userId,
                    profile.username(), profile.nickname());
        } catch (BizException ex) {
            // 失败亦记日志（AC-1）；failReason=业务错误码（含 LoginGuard 判定结果映射的 ACCOUNT_LOCKED）
            loginLogRecorder.record(LoginLogRecorder.Entry.capture(normalized, UserTokenIssuer.CHANNEL_B,
                    clientIp, userAgent, false, ex.errorCode().code()));
            throw ex;
        }
    }

    /**
     * 验证码是否必须（REQ-20261117-011514）：按客户端声称租户实时查策略（不引入缓存，确认记录④）；
     * 策略查询失败 fail-closed 视为开启（AC-6 不因降级放宽安全，WARN 记录）。
     */
    private boolean captchaRequired(String tenantCode) {
        try {
            CaptchaPolicyView policy = systemConfigGateway.findCaptchaPolicy(tenantCode);
            return policy != null && policy.captchaEnabled();
        } catch (RuntimeException ex) {
            log.warn("验证码策略查询失败，fail-closed 视为开启：tenantCode={}", tenantCode, ex);
            return true;
        }
    }

    /**
     * 租户可登录性校验（REQ-20261117-025200 确认记录③）：by-code 实查租户目录——
     * 非 ENABLED（停用级联第二道闸，与画像 effectiveStatus 同语义）或派生已过期
     * （expires 过且无有效宽限，REQ-20261122-150802 确认②宽限期内放行）统一 LOGIN_FAILED
     * （FR-005 不泄露租户状态差异）；目录查询失败 fail-closed 视为不可登录（登录面从严，WARN 记录）。
     */
    private void verifyTenantLoginable(String tenantCode) {
        TenantInternalView tenant;
        try {
            tenant = tenantDirectoryGateway.findByCode(tenantCode);
        } catch (RuntimeException ex) {
            log.warn("租户目录查询失败，登录 fail-closed 拒绝：tenantCode={}", tenantCode, ex);
            throw new BizException(AuthError.LOGIN_FAILED);
        }
        Instant now = Instant.now();
        if (tenant == null
                || !EnableStatus.ENABLED.name().equals(tenant.status())
                || derivedExpired(now, tenant.expiresAt(), tenant.graceUntil())) {
            throw new BizException(AuthError.LOGIN_FAILED);
        }
    }

    /**
     * 派生过期判定（REQ-20261122-150802 确认②四视觉态读时判定）：expires 未到或无 expires=启用中；
     * expires 过且 grace 未过=宽限期（仍可登录，能力照旧）；grace 也过（或 expires 过且无 grace）=
     * 已过期（派生自动过期，无定时任务）。
     */
    private static boolean derivedExpired(Instant now, Instant expiresAt, Instant graceUntil) {
        if (expiresAt == null || !expiresAt.isBefore(now)) {
            return false;
        }
        return graceUntil == null || !graceUntil.isAfter(now);
    }

    /** 互踢：旧活跃会话置 KICKED（事务）→ 撤销集写入（事务外，失败降级 WARN 不阻断登录）。 */
    private void kickPreviousSessions(Long userId) {
        List<SessionRecorder.RevocableToken> previous = sessionRecorder.kickActiveSessions(userId);
        SessionRevocations.bestEffort(sessionRedis, previous, userId, "互踢");
    }
}
