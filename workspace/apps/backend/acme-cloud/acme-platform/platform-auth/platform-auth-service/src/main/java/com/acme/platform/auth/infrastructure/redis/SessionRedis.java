package com.acme.platform.auth.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话运行态 Redis 支撑（架构设计 4.3，Q-D02）：单会话索引 + 撤销集，键带 auth: 服务前缀（12.3.2）；
 * 撤销集由网关每请求直查（即时失效 BR-006）。
 * 失败语义分级：登录路径 fail-open（互踢 / 索引 best-effort）；登出 / 重置撤销路径显式失败
 * （不得出现「响应成功但令牌仍可用」状态，FR-004 异常分支）。
 */
@Component
public class SessionRedis {

    private static final Logger log = LoggerFactory.getLogger(SessionRedis.class);

    static final String SESSION_INDEX_PREFIX = "auth:session:user:";
    static final String REVOKE_KEY_PREFIX = "auth:revoke:";

    private final StringRedisTemplate redis;

    public SessionRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 单会话索引：当前活跃 tokenId（缺失 / 异常返回 null，登录互踢 fail-open）。 */
    public String currentSessionTokenId(Long userId) {
        try {
            return redis.opsForValue().get(SESSION_INDEX_PREFIX + userId);
        } catch (RuntimeException ex) {
            log.warn("单会话索引读取失败，FAIL_OPEN 跳过互踢预检：userId={}", userId);
            return null;
        }
    }

    /** 写单会话索引（TTL=令牌剩余有效期，自然过期回收；best-effort）。 */
    public void indexSession(Long userId, String tokenId, long ttlSeconds) {
        try {
            redis.opsForValue().set(SESSION_INDEX_PREFIX + userId, tokenId, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ex) {
            log.warn("单会话索引写入失败（不影响登录）：userId={}", userId);
        }
    }

    /** 撤销集查询：true=已撤销；异常上抛（登出幂等判定由调用方收敛为失败路径）。 */
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKE_KEY_PREFIX + tokenId));
    }

    /**
     * 写撤销集（TTL=令牌剩余有效期，自然过期回收，登出 / 互踢 / 重置共用）。
     *
     * @throws RedisKeyWriteFailedException Redis 异常——登出 / 重置路径必须失败响应（FR-004）
     */
    public void revoke(String tokenId, long ttlSeconds) {
        try {
            redis.opsForValue().set(REVOKE_KEY_PREFIX + tokenId, "1",
                    Duration.ofSeconds(Math.max(1, ttlSeconds)));
        } catch (RuntimeException ex) {
            throw new RedisKeyWriteFailedException(tokenId, ex);
        }
    }

    /** 撤销写入失败承载（调用方映射 LOGOUT_FAILED / 系统错误）。 */
    public static final class RedisKeyWriteFailedException extends RuntimeException {

        public RedisKeyWriteFailedException(String tokenId, Throwable cause) {
            super("撤销集写入失败：" + tokenId, cause);
        }
    }
}
