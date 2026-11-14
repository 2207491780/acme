package com.acme.platform.system.notify;

import com.acme.framework.security.support.TokenKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Optional;

/**
 * WS 握手用户令牌验签（确认记录④⑦⑬：复用用户令牌域与网关验签语义，密钥键=网关同键单源
 * {@code acme.gateway.user-token.hmac-secret}）：
 * HS256（密钥=用户令牌域 ACME_USER_TOKEN_HMAC_SECRET，与 platform-auth 签发 / 网关验签同源）
 * + exp（时钟偏移）+ issuer 强制匹配 + tokenType 必须访问令牌（11.4 刷新令牌不得鉴权，AuthFilter 同口径）。
 * 验签失败一律返回 empty，由握手拦截器收敛为 401 拒绝（WS 无 Result 信封语义）。
 */
@Component
public class NotifyTokenVerifier {

    private final SecretKey key;
    private final String issuer;
    private final long clockSkewSeconds;

    public NotifyTokenVerifier(NotifyUserTokenProperties properties) {
        // 14.3.3：空密钥显式失败，不落默认密钥（派生单一失败点 TokenKeys）
        this.key = TokenKeys.hmacKey(properties.getHmacSecret());
        this.issuer = properties.getIssuer();
        this.clockSkewSeconds = properties.getClockSkewSeconds();
    }

    /** 验签通过返回 userId（claims subject）；失败 / 过期 / 刷新令牌 / 无主体返回 empty。 */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Claims claims;
        try {
            var parser = Jwts.parser().verifyWith(key).clockSkewSeconds(clockSkewSeconds);
            if (issuer != null && !issuer.isBlank()) {
                parser.requireIssuer(issuer);
            }
            claims = parser.build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            // ExpiredJwtException 为 JwtException 子类（过期同拒绝语义）
            return Optional.empty();
        }
        if (!"access".equals(claims.get("tokenType", String.class))) {
            return Optional.empty();
        }
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }
}
