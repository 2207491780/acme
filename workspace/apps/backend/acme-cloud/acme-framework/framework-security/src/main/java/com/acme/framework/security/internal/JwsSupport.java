package com.acme.framework.security.internal;

import com.acme.framework.security.support.TokenKeys;

import javax.crypto.SecretKey;

/**
 * JWS HS256 支撑（Q-D01、7.7）：jjwt-jackson 的 Jackson 2 触碰面收敛 internal（Modules.md 3.5）。
 * 密钥派生单一失败点在 {@link TokenKeys}（Q-D001、14.3.3），本类仅作 internal 侧入口。
 */
public final class JwsSupport {

    private JwsSupport() {
    }

    /** HMAC 共享密钥（14.3.3：空密钥显式失败，不落默认密钥）。 */
    public static SecretKey hmacKey(String hmacSecret) {
        return TokenKeys.hmacKey(hmacSecret);
    }
}
