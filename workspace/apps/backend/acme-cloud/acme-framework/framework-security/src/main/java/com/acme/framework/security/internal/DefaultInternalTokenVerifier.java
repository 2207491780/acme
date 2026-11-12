package com.acme.framework.security.internal;

import com.acme.common.core.BizException;
import com.acme.common.context.Channel;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.support.InternalTokenClaims;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.support.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内部令牌校验缺省实现（FR-014、7.7：JWS HS256 + jjwt，Q-D01）；jjwt-jackson 的 Jackson 2 触碰面收敛本包（Modules.md 3.5）。
 * 校验=验签 + exp（时钟偏移）+ issuer + tokenType/channel 解析（7.7）。
 */
public class DefaultInternalTokenVerifier implements InternalTokenVerifier {

    private final SecretKey key;
    private final String issuer;
    private final long clockSkewSeconds;

    public DefaultInternalTokenVerifier(SecurityProperties.Token token) {
        this.key = JwsSupport.hmacKey(token.getHmacSecret());
        this.issuer = token.getIssuer();
        this.clockSkewSeconds = token.getClockSkewSeconds();
    }

    @Override
    public InternalTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(SecurityError.TOKEN_MISSING);
        }
        Claims claims;
        try {
            var parser = Jwts.parser().verifyWith(key).clockSkewSeconds(clockSkewSeconds);
            if (issuer != null && !issuer.isBlank()) {
                parser.requireIssuer(issuer);
            }
            claims = parser.build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BizException(SecurityError.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(SecurityError.TOKEN_INVALID);
        }
        return new InternalTokenClaims(
                claims.getSubject(),
                claims.get("tenantId", String.class),
                parseChannel(claims.get("channel", String.class)),
                roles(claims),
                TokenType.parse(claims.get("tokenType", String.class)),
                claims.getIssuer(),
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
