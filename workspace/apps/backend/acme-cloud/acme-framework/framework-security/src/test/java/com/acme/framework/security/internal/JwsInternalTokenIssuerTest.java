package com.acme.framework.security.internal;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.support.InternalTokenClaims;
import com.acme.framework.security.support.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部令牌签发-校验往返测试（FR-022/014、7.7、Q-D001 签发/校验同居）。
 */
class JwsInternalTokenIssuerTest {

    private static final String SECRET_A = "secret-a-secret-a-secret-a-secret-a";
    private static final String SECRET_B = "secret-b-secret-b-secret-b-secret-b";

    private static SecurityProperties.Token token(String secret) {
        SecurityProperties.Token config = new SecurityProperties.Token();
        config.setHmacSecret(secret);
        config.setIssuer("acme-gateway");
        return config;
    }

    @Test
    void issueAndVerifyRoundTrip() {
        JwsInternalTokenIssuer issuer = new JwsInternalTokenIssuer(token(SECRET_A));
        DefaultInternalTokenVerifier verifier = new DefaultInternalTokenVerifier(token(SECRET_A));
        String jws = issuer.issue(new AuthenticatedSubject("u-1", "t-1", Channel.B, Set.of("admin")));
        InternalTokenClaims claims = verifier.verify(jws);
        assertThat(claims.userId()).isEqualTo("u-1");
        assertThat(claims.tenantId()).isEqualTo("t-1");
        assertThat(claims.channel()).isEqualTo(Channel.B);
        assertThat(claims.roles()).containsExactly("admin");
        assertThat(claims.tokenType()).isEqualTo(TokenType.ACCESS);
        assertThat(claims.issuer()).isEqualTo("acme-gateway");
    }

    @Test
    void verifierRejectsDifferentSecret() {
        JwsInternalTokenIssuer issuer = new JwsInternalTokenIssuer(token(SECRET_A));
        DefaultInternalTokenVerifier verifier = new DefaultInternalTokenVerifier(token(SECRET_B));
        String jws = issuer.issue(new AuthenticatedSubject("u-1", "t-1", null, Set.of()));
        assertThatThrownBy(() -> verifier.verify(jws))
                .hasMessageContaining("令牌");
    }

    @Test
    void verifierRejectsWrongIssuer() {
        JwsInternalTokenIssuer issuer = new JwsInternalTokenIssuer(token(SECRET_A));
        SecurityProperties.Token otherIssuer = token(SECRET_A);
        otherIssuer.setIssuer("acme-other");
        DefaultInternalTokenVerifier verifier = new DefaultInternalTokenVerifier(otherIssuer);
        String jws = issuer.issue(new AuthenticatedSubject("u-1", null, null, Set.of()));
        assertThatThrownBy(() -> verifier.verify(jws))
                .hasMessageContaining("令牌");
    }

    @Test
    void blankSecretFailsExplicitly() {
        assertThatThrownBy(() -> new JwsInternalTokenIssuer(new SecurityProperties.Token()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    @Test
    void emptySubjectRejected() {
        JwsInternalTokenIssuer issuer = new JwsInternalTokenIssuer(token(SECRET_A));
        assertThatThrownBy(() -> issuer.issue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
