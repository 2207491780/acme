package com.acme.framework.gateway.internal;

import com.acme.common.core.BizException;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenVerifier;
import com.acme.framework.security.token.UserAccessClaims;
import com.acme.framework.security.token.UserTokenAccessVerifier;
import com.acme.framework.security.support.SecurityError;

/**
 * 用户令牌验签缺省实现（FR-019、8.2；Q-D01 家族 JWS HS256）——REQ-20261118-002130 确认④起
 * 委托 framework-security {@link UserTokenAccessVerifier}（无 WebFlux 载体，验签逻辑单源，
 * 单体 BootstrapAuthFilter 复用同一实现避免双实现漂移）；本类收敛网关语义：
 * SPI 形态（{@link UserTokenVerifier}）+ 网关错误码段映射（行为零变化——对外仍为
 * {@link GatewayError#AUTH_FAILED}/{@link GatewayError#TOKEN_EXPIRED} 的 8.2 口径）。
 */
public class DefaultUserTokenVerifier implements UserTokenVerifier {

    private final UserTokenAccessVerifier delegate;

    public DefaultUserTokenVerifier(GatewayProperties.UserToken config) {
        // 14.3.3：空密钥显式失败，不落默认密钥（派生单一失败点 TokenKeys）
        this.delegate = new UserTokenAccessVerifier(config.getHmacSecret(), config.getIssuer(),
                config.getClockSkewSeconds());
    }

    @Override
    public UserTokenClaims verify(String token) {
        try {
            UserAccessClaims claims = delegate.verifyAccess(token);
            return new UserTokenClaims(claims.userId(), claims.tenantId(), claims.channel(),
                    claims.roles(), claims.tokenType(), claims.tokenId());
        } catch (BizException ex) {
            // 网关错误码段映射（SYS-03 段）：安全段过期/无效语义 → 网关 8.2 口径，信封形态零变化
            if (SecurityError.TOKEN_EXPIRED.code().equals(ex.errorCode().code())) {
                throw new BizException(GatewayError.TOKEN_EXPIRED);
            }
            throw new BizException(GatewayError.AUTH_FAILED);
        }
    }
}
