package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.domain.AuthConsumerAccount;
import com.acme.platform.auth.domain.ConsumerStatus;
import com.acme.platform.auth.infrastructure.redis.CaptchaRedis;
import com.acme.platform.auth.infrastructure.repository.AuthConsumerAccountRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.springframework.stereotype.Service;

/**
 * C 端验证码登录用例（consumer-auth-refresh-dataperm，11.4 C 端「令牌 + 刷新」）：
 * 编排不开启整体事务（无跨表强一致需求；账户 upsert 独立小事务经仓库语义）；
 * 首登自动注册（手机号即账户）；C 端多端并存（不互踢）；claims：channel=C、roles 空、
 * tenantId=保留值 {@link #CONSUMER_TENANT}（C 端逻辑租户，下游 @TenantId 过滤同域隔离）。
 */
@Service
public class ConsumerLoginUseCase {

    /** C 端逻辑租户保留值（C 端用户不归属管理租户体系，12.4.1 数据域按此隔离）。 */
    public static final String CONSUMER_TENANT = "consumer";

    private final CaptchaRedis captchaRedis;
    private final AuthConsumerAccountRepository accountRepository;
    private final SessionRecorder sessionRecorder;
    private final UserTokenIssuer tokenIssuer;
    private final AuthTokenProperties.Consumer consumerProperties;

    public ConsumerLoginUseCase(CaptchaRedis captchaRedis,
                                AuthConsumerAccountRepository accountRepository,
                                SessionRecorder sessionRecorder,
                                UserTokenIssuer tokenIssuer,
                                AuthTokenProperties tokenProperties) {
        this.captchaRedis = captchaRedis;
        this.accountRepository = accountRepository;
        this.sessionRecorder = sessionRecorder;
        this.tokenIssuer = tokenIssuer;
        this.consumerProperties = tokenProperties.getConsumer();
    }

    /** 验证码登录（验证码一次性消费；错误达阈值作废需重发；账户停用拒绝）。 */
    public AppTokenIssued login(String phone, String captcha) {
        if (!captchaRedis.verify(phone, captcha)) {
            throw new BizException(captchaRedis.exists(phone)
                    ? AuthError.CAPTCHA_INVALID : AuthError.CAPTCHA_EXPIRED);
        }
        AuthConsumerAccount account = accountRepository.findByPhone(phone)
                .orElseGet(() -> accountRepository.save(new AuthConsumerAccount(phone)));
        if (account.getStatus() == ConsumerStatus.DISABLED) {
            throw new BizException(AuthError.CONSUMER_DISABLED);
        }
        account.markLogin();
        accountRepository.save(account);

        UserTokenIssuer.IssuedToken access = tokenIssuer.issue(account.getId(), CONSUMER_TENANT,
                java.util.Set.of(), UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_ACCESS,
                consumerProperties.getAccessTtlSeconds());
        UserTokenIssuer.IssuedToken refresh = tokenIssuer.issue(account.getId(), CONSUMER_TENANT,
                java.util.Set.of(), UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH,
                consumerProperties.getRefreshTtlSeconds());
        sessionRecorder.recordConsumer(access.tokenId(), refresh.tokenId(), account.getId(), CONSUMER_TENANT,
                access.issuedAt(), access.expiresAt(), refresh.expiresAt());
        return new AppTokenIssued(access.token(), refresh.token(),
                access.expiresAt(), refresh.expiresAt(), account.getId(), phone);
    }
}
