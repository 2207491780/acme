package com.acme.framework.gateway.internal;

import com.acme.common.core.BizException;
import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenVerifier;
import com.acme.framework.security.support.TokenKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户令牌验签缺省实现（FR-019、8.2；Q-D01 家族 JWS HS256）；jjwt-jackson 的 Jackson 2 触碰面收敛本包（Modules.md 3.5）。
 * 密钥域独立于内部令牌（acme.gateway.user-token.*，11.6.2 两类令牌不得互验）；
 * 校验=验签 + exp（时钟偏移）+ issuer 强制匹配 + tokenType 解析（8.2）。
 * 用户令牌格式属 platform-auth 未来契约（8.2），P3 验收供给见架构设计 11.3。
 */
public class DefaultUserTokenVerifier implements UserTokenVerifier {

    private final SecretKey key;
    private final String issuer;
    private final long clockSkewSeconds;

    public DefaultUserTokenVerifier(GatewayProperties.UserToken config) {
        // 14.3.3：空密钥显式失败，不落默认密钥（派生单一失败点 TokenKeys）
        this.key = TokenKeys.hmacKey(config.getHmacSecret());
        this.issuer = config.getIssuer();
        this.clockSkewSeconds = config.getClockSkewSeconds();
    }

    @Override
    public UserTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(GatewayError.AUTH_FAILED);
        }
        Claims claims;
        try {
            var parser = Jwts.parser().verifyWith(key).clockSkewSeconds(clockSkewSeconds);
            if (issuer != null && !issuer.isBlank()) {
                parser.requireIssuer(issuer);
            }
            claims = parser.build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BizException(GatewayError.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(GatewayError.AUTH_FAILED);
        }
        return new UserTokenClaims(
                claims.getSubject(),
                claims.get("tenantId", String.class),
                parseChannel(claims.get("channel", String.class)),
                roles(claims),
                claims.get("tokenType", String.class),
                claims.getId());
    }

    private static Channel parseChannel(String rawChannel) {
        if (rawChannel == null || rawChannel.isBlank()) {
            return null;
        }
        try {
            return Channel.valueOf(rawChannel.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Set<String> roles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof List<?> list) {
            Set<String> roles = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null) {
                    roles.add(String.valueOf(item));
                }
            }
            return roles;
        }
        return Set.of();
    }
}
