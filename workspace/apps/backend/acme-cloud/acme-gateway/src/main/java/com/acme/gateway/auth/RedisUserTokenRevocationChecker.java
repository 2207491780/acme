package com.acme.gateway.auth;

import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 网关侧用户令牌撤销检查 Redis 实现（Q-D02、FR-004 即时失效）：
 * 直查 platform-auth 写入的撤销集键 {@code auth:revoke:{jti}}（12.3.2 键带服务前缀），
 * 每请求一查、无本地缓存（Q-D02 取舍 #11，保证「即时」语义可判定）。
 * Redis 异常向上抛出，由 framework-gateway RevocationCheckFilter 按配置兜底（默认 FAIL_OPEN）。
 */
@Component
public class RedisUserTokenRevocationChecker implements UserTokenRevocationChecker {

    /** 撤销集键前缀（与 platform-auth 写入侧约定一致）。 */
    static final String REVOKE_KEY_PREFIX = "auth:revoke:";

    private final StringRedisTemplate redis;

    public RedisUserTokenRevocationChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        Boolean exists = redis.hasKey(REVOKE_KEY_PREFIX + tokenId);
        return Boolean.TRUE.equals(exists);
    }
}
