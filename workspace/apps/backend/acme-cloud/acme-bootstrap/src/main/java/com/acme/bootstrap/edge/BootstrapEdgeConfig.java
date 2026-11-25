package com.acme.bootstrap.edge;

import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.revocation.RevocationCheckPolicy;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.token.UserTokenAccessVerifier;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.notify.NotifyUserTokenProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 单体边缘认证装配（REQ-20261118-002130 确认④）：
 * <ul>
 *   <li>用户令牌验签器 {@link UserTokenAccessVerifier}——密钥域取 {@code acme.gateway.user-token.*}
 *       （与网关/WS 握手同键单源 {@link NotifyUserTokenProperties}，环境注入
 *       ACME_USER_TOKEN_HMAC_SECRET；与内部令牌密钥域隔离，11.6.2）；</li>
 *   <li>边缘认证过滤器 {@link BootstrapAuthFilter}——公开路径取 {@code acme.security.public-paths}
 *       （application-monolith.yml 维护三服务并集，InternalTokenAuthFilter 同清单）；
 *       撤销检查同 JVM 直调 {@link SessionRedis}（异常处置统一经 RevocationCheckPolicy，
 *       REQ-20261125-001736 AC-7 同网关口径）。</li>
 * </ul>
 * 内部令牌签发复用 framework-security 自动装配的 {@link InternalTokenIssuer}
 * （JwsInternalTokenIssuer，acme.security.token.* 域）。
 */
@Configuration(proxyBeanMethods = false)
public class BootstrapEdgeConfig {

    @Bean
    public UserTokenAccessVerifier userTokenAccessVerifier(NotifyUserTokenProperties userTokenProperties) {
        return new UserTokenAccessVerifier(userTokenProperties.getHmacSecret(),
                userTokenProperties.getIssuer(), userTokenProperties.getClockSkewSeconds());
    }

    @Bean
    public BootstrapAuthFilter bootstrapAuthFilter(UserTokenAccessVerifier verifier,
                                                   InternalTokenIssuer internalTokenIssuer,
                                                   SessionRedis sessionRedis,
                                                   RevocationCheckPolicy revocationCheckPolicy,
                                                   SecurityProperties securityProperties) {
        List<String> publicPaths = securityProperties.getPublicPaths();
        return new BootstrapAuthFilter(verifier, internalTokenIssuer, sessionRedis,
                revocationCheckPolicy, publicPaths);
    }
}
