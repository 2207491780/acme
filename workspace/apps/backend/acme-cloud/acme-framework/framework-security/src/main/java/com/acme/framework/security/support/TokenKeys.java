package com.acme.framework.security.support;

import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 令牌密钥派生单一失败点（Q-D001 签发/校验同居接缝、14.3.3）：网关用户令牌验签与内部令牌
 * 签发/校验同源引用；空密钥显式失败，不落默认密钥。用户/内部令牌密钥域彼此隔离（11.6.2），
 * 调用方须各自注入对应密钥，不得共用。
 */
public final class TokenKeys {

    private TokenKeys() {
    }

    /** HMAC 共享密钥（14.3.3：空密钥显式失败）。 */
    public static SecretKey hmacKey(String hmacSecret) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException("令牌 hmac-secret 未注入（7.7、14.3.3：不落默认密钥）");
        }
        return Keys.hmacShaKeyFor(hmacSecret.getBytes(StandardCharsets.UTF_8));
    }
}
