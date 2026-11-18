package com.acme.framework.security.token;

import com.acme.common.context.Channel;
import com.acme.common.core.BizException;
import com.acme.framework.security.support.SecurityError;
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
 * 用户访问令牌验签（REQ-20261118-002130 确认④：framework-gateway {@code DefaultUserTokenVerifier}
 * 的无 WebFlux 抽取载体——纯 jjwt 解析，Servlet/单体形态可直接复用，避免双实现漂移）。
 * 校验=验签 + exp（时钟偏移）+ issuer 强制匹配 + claims 解析（8.2 语义对齐）：
 * 令牌格式属 platform-auth 契约（8.2，签发集见 {@link UserAccessClaims} 类注）。
 * 密钥域独立于内部令牌（11.6.2 两类令牌不得互验），派生经 {@link TokenKeys} 单一失败点（14.3.3）。
 * 失败语义：过期 → {@link SecurityError#TOKEN_EXPIRED}（401）；其余（空令牌/签名/issuer/格式）→
 * {@link SecurityError#UNAUTHORIZED}（401）。jjwt-jackson 的 Jackson 2 触碰面收敛本类（Modules.md 3.5）。
 */
public class UserTokenAccessVerifier {

    private final SecretKey key;
    private final String issuer;
    private final long clockSkewSeconds;

    /**
     * 构造验签器（14.3.3：空密钥显式失败，不落默认密钥；字段语义与网关
     * {@code acme.gateway.user-token.*} 同源——hmac-secret/issuer/clockSkewSeconds）。
     */
    public UserTokenAccessVerifier(String hmacSecret, String issuer, long clockSkewSeconds) {
        this.key = TokenKeys.hmacKey(hmacSecret);
        this.issuer = issuer;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * 验签并解析用户访问令牌（FR-019、8.2）。
     *
     * @param token 用户令牌（JWS compact 形态）
     * @return 声明摘要（恒非 null）
     * @throws BizException 过期（TOKEN_EXPIRED）或无效（UNAUTHORIZED），均 401
     */
    public UserAccessClaims verifyAccess(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(SecurityError.UNAUTHORIZED);
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
            throw new BizException(SecurityError.UNAUTHORIZED);
        }
        return new UserAccessClaims(
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
