package com.acme.framework.gateway.internal;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.internal.JwsInternalTokenIssuer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户令牌验签测试（FR-019、8.2、11.6.2 令牌域隔离）：签发-校验往返、密钥域/issuer 隔离、内部令牌不可当用户令牌。
 */
class DefaultUserTokenVerifierTest {

    private static final String USER_SECRET = "user-secret-user-secret-user-secret";
    private static final String INTERNAL_SECRET = "internal-secret-internal-secret-int";

    private static GatewayProperties.UserToken userToken(String secret) {
        GatewayProperties.UserToken config = new GatewayProperties.UserToken();
        config.setHmacSecret(secret);
        config.setIssuer("acme-auth");
        return config;
    }

    private static String userJws(String secret, String issuer, String tokenType) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .id("jti-1")
                .issuer(issuer)
                .subject("u-1")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .claim("tenantId", "t-1")
                .claim("channel", "B")
                .claim("roles", Set.of("admin"))
                .claim("tokenType", tokenType)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void verifyUserTokenRoundTrip() {
        DefaultUserTokenVerifier verifier = new DefaultUserTokenVerifier(userToken(USER_SECRET));
        UserTokenClaims claims = verifier.verify(userJws(USER_SECRET, "acme-auth", "access"));
        assertThat(claims.userId()).isEqualTo("u-1");
        assertThat(claims.tenantId()).isEqualTo("t-1");
        assertThat(claims.channel()).isEqualTo(Channel.B);
        assertThat(claims.tokenType()).isEqualTo("access");
    }

    @Test
    void rejectsWrongSecret() {
        DefaultUserTokenVerifier verifier = new DefaultUserTokenVerifier(
                userToken("other-secret-other-secret-other-se"));
        assertThatThrownBy(() -> verifier.verify(userJws(USER_SECRET, "acme-auth", "access")))
                .hasMessageContaining("验签");
    }

    @Test
    void rejectsWrongIssuer() {
        DefaultUserTokenVerifier verifier = new DefaultUserTokenVerifier(userToken(USER_SECRET));
        assertThatThrownBy(() -> verifier.verify(userJws(USER_SECRET, "acme-evil", "access")))
                .hasMessageContaining("验签");
    }

    @Test
    void internalTokenNotAcceptedAsUserToken() {
        // 11.6.2：两类令牌不得互验——内部令牌（独立密钥域+issuer）在网关验签侧必须失败
        SecurityProperties.Token internalConfig = new SecurityProperties.Token();
        internalConfig.setHmacSecret(INTERNAL_SECRET);
        internalConfig.setIssuer("acme-gateway");
        String internalJws = new JwsInternalTokenIssuer(internalConfig)
                .issue(new AuthenticatedSubject("u-1", "t-1", Channel.B, Set.of("admin")));
        DefaultUserTokenVerifier verifier = new DefaultUserTokenVerifier(userToken(USER_SECRET));
        assertThatThrownBy(() -> verifier.verify(internalJws))
                .hasMessageContaining("验签");
    }

    @Test
    void blankSecretFailsExplicitly() {
        assertThatThrownBy(() -> new DefaultUserTokenVerifier(new GatewayProperties.UserToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    @Test
    void blankTokenRejected() {
        DefaultUserTokenVerifier verifier = new DefaultUserTokenVerifier(userToken(USER_SECRET));
        assertThatThrownBy(() -> verifier.verify(" "))
                .hasMessageContaining("验签");
    }
}
