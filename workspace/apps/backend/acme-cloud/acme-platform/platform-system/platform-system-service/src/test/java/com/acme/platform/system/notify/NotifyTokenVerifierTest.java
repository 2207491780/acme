package com.acme.platform.system.notify;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS 握手用户令牌验签单测（REQ-20261114-120102，AC-6 服务侧语义）：
 * 有效访问令牌通过；过期 / 错误签发方 / 刷新令牌 / 伪造签名 / 空令牌一律拒绝。
 */
class NotifyTokenVerifierTest {

    private static final String SECRET = "notify-ws-handshake-test-secret-0123456789abcdef";

    private static NotifyTokenVerifier verifier() {
        NotifyUserTokenProperties properties = new NotifyUserTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setIssuer("acme-auth");
        return new NotifyTokenVerifier(properties);
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String issuer, String subject, String tokenType, Date expiration,
                               SecretKey signingKey) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .claim("tokenType", tokenType)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    @Test
    void validAccessTokenVerifiesToUserId() {
        String token = sign("acme-auth", "42", "access",
                new Date(System.currentTimeMillis() + 60_000), key());
        assertThat(verifier().verify(token)).isEqualTo(Optional.of("42"));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = sign("acme-auth", "42", "access",
                new Date(System.currentTimeMillis() - 3_600_000), key());
        assertThat(verifier().verify(token)).isEmpty();
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = sign("acme-other", "42", "access",
                new Date(System.currentTimeMillis() + 60_000), key());
        assertThat(verifier().verify(token)).isEmpty();
    }

    @Test
    void refreshTokenTypeIsRejected() {
        // 11.4 刷新令牌不得鉴权（AuthFilter 同口径）
        String token = sign("acme-auth", "42", "refresh",
                new Date(System.currentTimeMillis() + 60_000), key());
        assertThat(verifier().verify(token)).isEmpty();
    }

    @Test
    void forgedSignatureIsRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor("another-secret-0123456789abcdef-0123456789".getBytes(
                StandardCharsets.UTF_8));
        String token = sign("acme-auth", "42", "access",
                new Date(System.currentTimeMillis() + 60_000), otherKey);
        assertThat(verifier().verify(token)).isEmpty();
    }

    @Test
    void blankOrGarbageTokenIsRejected() {
        assertThat(verifier().verify(null)).isEmpty();
        assertThat(verifier().verify("")).isEmpty();
        assertThat(verifier().verify("not-a-jwt")).isEmpty();
    }
}
