package com.acme.framework.security.token;

import com.acme.common.context.Channel;
import com.acme.common.core.BizException;
import com.acme.framework.security.support.SecurityError;
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
 * 用户令牌验签抽取载体测试（REQ-20261118-002130 确认④；语义对齐原 framework-gateway
 * DefaultUserTokenVerifierTest 四分支）：签发-验签往返、签名篡改拒绝、过期拒绝、issuer 不符拒绝；
 * 另覆盖空密钥显式失败与内部令牌密钥域隔离（11.6.2）。
 */
class UserTokenAccessVerifierTest {

    private static final String USER_SECRET = "user-secret-user-secret-user-secret";
    private static final String OTHER_SECRET = "other-secret-other-secret-other-se";
    private static final String INTERNAL_SECRET = "internal-secret-internal-secret-int";

    private static UserTokenAccessVerifier verifier(String secret, String issuer) {
        return new UserTokenAccessVerifier(secret, issuer, 30);
    }

    private static String userJws(String secret, String issuer, String tokenType, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .id("jti-1")
                .issuer(issuer)
                .subject("42")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .claim("tenantId", "default")
                .claim("channel", "B")
                .claim("roles", Set.of("super_admin"))
                .claim("tokenType", tokenType)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 分支①：合法令牌往返（claims 契约对齐 UserTokenIssuer 签发集）。 */
    @Test
    void verifyRoundTripParsesAllClaims() {
        UserTokenAccessVerifier verifier = verifier(USER_SECRET, "acme-auth");
        UserAccessClaims claims = verifier.verifyAccess(userJws(USER_SECRET, "acme-auth", "access",
                60_000));
        assertThat(claims.userId()).isEqualTo("42");
        assertThat(claims.tenantId()).isEqualTo("default");
        assertThat(claims.channel()).isEqualTo(Channel.B);
        assertThat(claims.roles()).containsExactly("super_admin");
        assertThat(claims.tokenType()).isEqualTo("access");
        assertThat(claims.tokenId()).isEqualTo("jti-1");
    }

    /** 分支②：签名篡改（密钥不符）→ 401 UNAUTHORIZED。 */
    @Test
    void rejectsTamperedSignature() {
        UserTokenAccessVerifier verifier = verifier(OTHER_SECRET, "acme-auth");
        assertThatThrownBy(() -> verifier.verifyAccess(userJws(USER_SECRET, "acme-auth", "access",
                60_000)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SecurityError.UNAUTHORIZED.code()));
    }

    /** 分支③：过期令牌 → 401 TOKEN_EXPIRED（过期与无效错误语义分离）。 */
    @Test
    void rejectsExpiredToken() {
        UserTokenAccessVerifier verifier = verifier(USER_SECRET, "acme-auth");
        assertThatThrownBy(() -> verifier.verifyAccess(userJws(USER_SECRET, "acme-auth", "access",
                -60_000)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SecurityError.TOKEN_EXPIRED.code()));
    }

    /** 分支④：issuer 不符 → 401 UNAUTHORIZED（签发方强制匹配）。 */
    @Test
    void rejectsWrongIssuer() {
        UserTokenAccessVerifier verifier = verifier(USER_SECRET, "acme-auth");
        assertThatThrownBy(() -> verifier.verifyAccess(userJws(USER_SECRET, "acme-evil", "access",
                60_000)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SecurityError.UNAUTHORIZED.code()));
    }

    /** 空令牌与空密钥（14.3.3 不落默认密钥）。 */
    @Test
    void rejectsBlankTokenAndBlankSecret() {
        UserTokenAccessVerifier verifier = verifier(USER_SECRET, "acme-auth");
        assertThatThrownBy(() -> verifier.verifyAccess(" "))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SecurityError.UNAUTHORIZED.code()));
        assertThatThrownBy(() -> new UserTokenAccessVerifier("", "acme-auth", 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    /** 11.6.2 两类令牌不得互验：内部令牌（独立密钥域+issuer）在用户令牌验签侧必须失败。 */
    @Test
    void internalTokenNotAcceptedAsUserToken() {
        SecretKey internalKey = Keys.hmacShaKeyFor(INTERNAL_SECRET.getBytes(StandardCharsets.UTF_8));
        String internalJws = Jwts.builder()
                .id("i-1").issuer("acme-gateway").subject("platform-auth")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("tokenType", "access")
                .signWith(internalKey, Jwts.SIG.HS256)
                .compact();
        UserTokenAccessVerifier verifier = verifier(USER_SECRET, "acme-auth");
        assertThatThrownBy(() -> verifier.verifyAccess(internalJws))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SecurityError.UNAUTHORIZED.code()));
    }
}
