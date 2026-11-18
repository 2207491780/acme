package com.acme.gateway.auth;

import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 网关侧用户令牌撤销检查 Redis 实现（Q-D02、FR-004 即时失效）：
 * 直查 platform-auth 写入的撤销集键 {@code auth:revoke:{jti}}（12.3.2 键带服务前缀），
 * 每请求一查、无本地缓存（Q-D02 取舍 #11，保证「即时」语义可判定）。
 * Redis 异常向上抛出，由 framework-gateway RevocationCheckFilter 按配置兜底（默认 FAIL_OPEN）。
 *
 * <p>REQ-20261114-120105 delta1（每请求活跃，确认记录⑥）：搭载最近活跃更新——未撤销（即令牌有效）
 * 的请求，按 Lua 脚本单往返更新会话信息哈希 {@code auth:session:info:{tokenId}} 的
 * {@code lastActiveAt} 字段（epoch 毫秒）；EXISTS 守卫：键缺失（未写入/已过期）不建键防泄漏，
 * 也不改 TTL（TTL 归 platform-auth 写入侧）。写失败仅 WARN 跳过，不阻断请求、不影响撤销判定；
 * 不做节流（单次 HSET 开销可忽略，保证 AC-3 每请求语义可判定）。</p>
 */
@Component
public class RedisUserTokenRevocationChecker implements UserTokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisUserTokenRevocationChecker.class);

    /** 撤销集键前缀（与 platform-auth 写入侧约定一致）。 */
    static final String REVOKE_KEY_PREFIX = "auth:revoke:";

    /** 会话信息键前缀（与 platform-auth SessionRedis 写入侧一致：Redis Hash）。 */
    static final String SESSION_INFO_PREFIX = "auth:session:info:";

    /** 最近活跃字段（与会话信息哈希布局一致）。 */
    static final String SESSION_INFO_FIELD_LAST_ACTIVE = "lastActiveAt";

    /**
     * 最近活跃更新脚本：键存在才 HSET（防键泄漏；不改 TTL）——1=已更新，0=键缺失跳过。
     */
    private static final DefaultRedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('HSET', KEYS[1], '%s', ARGV[1])
              return 1
            end
            return 0
            """.formatted(SESSION_INFO_FIELD_LAST_ACTIVE), Long.class);

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
        boolean revoked = Boolean.TRUE.equals(exists);
        if (!revoked) {
            touchLastActive(tokenId);
        }
        return revoked;
    }

    /** 最近活跃更新（每请求；失败仅 WARN 跳过，不阻断请求、不影响撤销判定结果）。 */
    private void touchLastActive(String tokenId) {
        try {
            redis.execute(TOUCH_SCRIPT, List.of(SESSION_INFO_PREFIX + tokenId),
                    Long.toString(Instant.now().toEpochMilli()));
        } catch (RuntimeException ex) {
            log.warn("会话最近活跃更新失败（不阻断请求）：tokenId={}", tokenId);
        }
    }
}
