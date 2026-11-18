package com.acme.platform.auth.internal.token;

import com.acme.common.core.BizException;
import com.acme.platform.auth.config.AuthTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户令牌签发契约测试（架构设计 4.1：claims 契约对齐网关 DefaultUserTokenVerifier；
 * AC-006 令牌签发含端别声明；AC-011 伪造拒绝）。
 */
class UserTokenIssuerTest {

    private static final String SECRET = "issuer-contract-test-secret-0123456789abcdef";

    private static UserTokenIssuer issuer(long ttlSeconds) {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setIssuer("acme-auth");
        properties.setTtlSeconds(ttlSeconds);
        return new UserTokenIssuer(properties);
    }

    @Test
    void claimsMatchGatewayVerifierContract() {
        Set<String> roles = new LinkedHashSet<>(Set.of("super_admin", "ops"));
        UserTokenIssuer.IssuedToken issued = issuer(1800).issue(1234567890123456789L, "default", roles);
        Claims claims = Jwts.parser().verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build().parseSignedClaims(issued.token()).getPayload();
        // 4.1 claims 契约逐项（网关 UserTokenClaims 解析面）
        assertThat(claims.getSubject()).isEqualTo("1234567890123456789");
        assertThat(claims.get("tenantId", String.class)).isEqualTo("default");
        assertThat(claims.get("channel", String.class)).isEqualTo("B");
        assertThat((java.util.List<Object>) claims.get("roles"))
                .containsExactlyInAnyOrder("super_admin", "ops");
        assertThat(claims.get("tokenType", String.class)).isEqualTo("access");
        assertThat(claims.getIssuer()).isEqualTo("acme-auth");
        assertThat(claims.getId()).isNotBlank().isEqualTo(issued.tokenId());
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void verifyRoundTrip() {
        UserTokenIssuer issuer = issuer(1800);
        UserTokenIssuer.IssuedToken issued = issuer.issue(1L, "default", Set.of());
        UserTokenIssuer.VerifiedToken verified = issuer.verify(issued.token());
        assertThat(verified.tokenId()).isEqualTo(issued.tokenId());
        // exp 经 JWS NumericDate（秒精度）承载，按秒截断后比对
        assertThat(verified.expiresAt())
                .isEqualTo(issued.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void tamperedTokenRejected() {
        UserTokenIssuer issuer = issuer(1800);
        String token = issuer.issue(1L, "default", Set.of()).token();
        // 伪造：非本密钥签发（网关侧 AuthFilter 同语义拒绝，AC-011）
        UserTokenIssuer forger = issuerWithDifferentSecret();
        assertThatThrownBy(() -> issuer.verify(forger.issue(1L, "default", Set.of()).token()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> issuer.verify(token.substring(0, token.length() - 3) + "xxx"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void expiredTokenRejected() {
        // ttl 为负 → 签发即过期
        UserTokenIssuer issuer = issuer(-60);
        UserTokenIssuer.IssuedToken issued = issuer.issue(1L, "default", Set.of());
        assertThatThrownBy(() -> issuer.verify(issued.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code()).isEqualTo("SYS-02-0003"));
    }

    @Test
    void blankSecretFailsFast() {
        AuthTokenProperties properties = new AuthTokenProperties();
        // 14.3.3：空密钥显式失败（TokenKeys 单一失败点）
        assertThatThrownBy(() -> new UserTokenIssuer(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    /** C 端双令牌（consumer-auth-refresh-dataperm）：channel=C、tokenType 区分 access/refresh、TTL 独立。 */
    @Test
    void consumerTokensCarryChannelAndType() {
        UserTokenIssuer issuer = issuer(1800);
        UserTokenIssuer.IssuedToken access = issuer.issue(9L, "consumer", java.util.Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_ACCESS, 7200);
        UserTokenIssuer.IssuedToken refresh = issuer.issue(9L, "consumer", java.util.Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH, 1209600);

        UserTokenIssuer.VerifiedToken verifiedAccess = issuer.verify(access.token());
        UserTokenIssuer.VerifiedToken verifiedRefresh = issuer.verify(refresh.token());
        assertThat(verifiedAccess.channel()).isEqualTo("C");
        assertThat(verifiedAccess.tokenType()).isEqualTo("access");
        assertThat(verifiedRefresh.channel()).isEqualTo("C");
        assertThat(verifiedRefresh.tokenType()).isEqualTo("refresh");
        // 两类 jti 独立（双令牌撤销互不混淆）
        assertThat(verifiedAccess.tokenId()).isNotEqualTo(verifiedRefresh.tokenId());
    }

    private static UserTokenIssuer issuerWithDifferentSecret() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret("another-secret-entirely-0123456789abcdef");
        properties.setIssuer("acme-auth");
        properties.setTtlSeconds(1800);
        return new UserTokenIssuer(properties);
    }
}
