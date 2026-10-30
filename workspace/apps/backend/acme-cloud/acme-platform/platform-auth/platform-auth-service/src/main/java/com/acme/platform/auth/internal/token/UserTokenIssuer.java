package com.acme.platform.auth.internal.token;

import com.acme.framework.security.support.TokenKeys;
import com.acme.platform.auth.config.AuthTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * 用户令牌签发与自校验（架构设计 4.1：JWS HS256，claims 契约对齐网关 DefaultUserTokenVerifier——
 * sub/tenantId/channel/roles/tokenType/jti/iss/exp/iat）。
 * jjwt-jackson 的 Jackson 2 触碰面收敛本 internal 包（Modules.md 3.5 隔离口径，架构设计 4.1 同型先例）；
 * 空密钥经 framework-security TokenKeys 显式失败（14.3.3）。
 */
public class UserTokenIssuer {

    /** 令牌类型（11.4：access 全端；refresh 仅 C 端刷新端点，网关拒绝其鉴权 API）。 */
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** B 端 channel claim；C 端 "C"（consumer-auth-refresh-dataperm）。 */
    public static final String CHANNEL_B = "B";
    public static final String CHANNEL_C = "C";

    private final SecretKey key;
    private final String issuer;
    private final long ttlSeconds;

    public UserTokenIssuer(AuthTokenProperties properties) {
        this.key = TokenKeys.hmacKey(properties.getHmacSecret());
        this.issuer = properties.getIssuer();
        this.ttlSeconds = properties.getTtlSeconds();
    }

    /** 签发结果载体。 */
    public record IssuedToken(String token, String tokenId, Instant issuedAt, Instant expiresAt) {
    }

    /** 自校验结果载体（登出/刷新链路自校验：jti / tokenType / channel / exp）。 */
    public record VerifiedToken(String tokenId, String tokenType, String channel, Instant expiresAt) {
    }

    /** 签发 B 端用户访问令牌（jti=会话 tokenId，UUID；短会话口径保持既有契约）。 */
    public IssuedToken issue(Long userId, String tenantId, Set<String> roles) {
        return issue(userId, tenantId, roles, CHANNEL_B, TOKEN_TYPE_ACCESS, ttlSeconds);
    }

    /** 通用签发（C 端 access/refresh 经此入口；channel/tokenType/TTL 由调用方指定）。 */
    public IssuedToken issue(Long userId, String tenantId, Set<String> roles,
                             String channel, String tokenType, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String tokenId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(tokenId)
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("tenantId", tenantId)
                .claim("channel", channel)
                .claim("roles", roles == null ? Set.of() : roles)
                .claim("tokenType", tokenType)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, tokenId, now, expiresAt);
    }

    /**
     * 校验并解析（登出/刷新链路自校验：验签 + issuer + exp；tokenType/channel 由调用方按端别口径判定）。
     *
     * @throws com.acme.common.core.BizException 无效 / 过期（SecurityError.UNAUTHORIZED / TOKEN_EXPIRED 语义）
     */
    public VerifiedToken verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                    .parseSignedClaims(token).getPayload();
            return new VerifiedToken(claims.getId(),
                    claims.get("tokenType", String.class),
                    claims.get("channel", String.class),
                    claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
        } catch (ExpiredJwtException ex) {
            throw new com.acme.common.core.BizException(
                    com.acme.framework.security.support.SecurityError.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new com.acme.common.core.BizException(
                    com.acme.framework.security.support.SecurityError.UNAUTHORIZED);
        }
    }
}
