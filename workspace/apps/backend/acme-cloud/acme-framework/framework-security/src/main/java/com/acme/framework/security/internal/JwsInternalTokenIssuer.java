package com.acme.framework.security.internal;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.support.InternalTokenIssuer;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * 内部令牌签发缺省实现（Q-D001 归位 framework-security、FR-022；7.7：JWS HS256 + jjwt，Q-D01）；
 * jjwt-jackson 的 Jackson 2 触碰面收敛本包。
 * claims 集合=iss/sub/tenantId/channel/roles[]/tokenType/iat/exp/jti（7.7）；deviceId/appVersion 不入 claims。
 */
public class JwsInternalTokenIssuer implements InternalTokenIssuer {

    private final SecretKey key;
    private final String issuer;
    private final long ttlMillis;

    public JwsInternalTokenIssuer(SecurityProperties.Token config) {
        this.key = JwsSupport.hmacKey(config.getHmacSecret());
        this.issuer = config.getIssuer();
        this.ttlMillis = config.getTtlSeconds() * 1000L;
    }

    @Override
    public String issue(AuthenticatedSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("签发主体不能为空（7.7）");
        }
        Date now = new Date();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject.userId())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .claim("tokenType", "access")
                .claim("roles", subject.roles());
        if (subject.tenantId() != null) {
            builder.claim("tenantId", subject.tenantId());
        }
        if (subject.channel() != null) {
            builder.claim("channel", subject.channel().name());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
