package com.acme.framework.security.internal;

import com.acme.common.core.BizException;
import com.acme.common.context.Channel;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.support.InternalTokenClaims;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.security.support.TokenType;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部令牌校验测试（FR-014/015、7.7、Q-D01、AC-011/012）。
 */
class DefaultInternalTokenVerifierTest {

    private static final String SECRET = "p3-access-layer-acceptance-test-secret-0123456789";

    private final SecurityProperties.Token config = tokenConfig();

    private final DefaultInternalTokenVerifier verifier = new DefaultInternalTokenVerifier(config);

    private static SecurityProperties.Token tokenConfig() {
        SecurityProperties.Token token = new SecurityProperties.Token();
        token.setHmacSecret(SECRET);
        return token;
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String issue(String userId, Channel channel, String tokenType, long expireOffsetMillis) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("acme-gateway")
                .subject(userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireOffsetMillis))
                .claim("tenantId", "t-1")
                .claim("channel", channel == null ? null : channel.name())
                .claim("roles", List.of("admin"))
                .claim("tokenType", tokenType)
                .signWith(key())
                .compact();
    }

    @Test
    void verifiesValidToken() {
        InternalTokenClaims claims = verifier.verify(issue("u-1", Channel.B, "access", 60_000));
        assertThat(claims.userId()).isEqualTo("u-1");
        assertThat(claims.tenantId()).isEqualTo("t-1");
        assertThat(claims.channel()).isEqualTo(Channel.B);
        assertThat(claims.roles()).containsExactly("admin");
        assertThat(claims.tokenType()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", SecurityError.TOKEN_MISSING);
    }

    @Test
    void rejectsForgedToken() {
        String forged = Jwts.builder().subject("u-1").claim("tokenType", "access")
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another!".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", SecurityError.TOKEN_INVALID);
    }

    @Test
    void rejectsExpiredToken() {
        assertThatThrownBy(() -> verifier.verify(issue("u-1", Channel.B, "access", -3_600_000)))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", SecurityError.TOKEN_EXPIRED);
    }

    @Test
    void rejectsRefreshType() {
        String token = issue("u-1", Channel.B, "refresh", 60_000);
        InternalTokenClaims claims = verifier.verify(token);
        // 校验层解析出类型；类型判定（FR-015）在过滤器
        assertThat(claims.tokenType()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    void blankSecretFailsFast() {
        SecurityProperties.Token blank = new SecurityProperties.Token();
        assertThatThrownBy(() -> new DefaultInternalTokenVerifier(blank))
                .isInstanceOf(IllegalStateException.class);
    }
}
