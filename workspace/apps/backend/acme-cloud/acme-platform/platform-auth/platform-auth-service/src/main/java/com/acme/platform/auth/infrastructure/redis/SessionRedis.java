package com.acme.platform.auth.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 会话运行态 Redis 支撑（架构设计 4.3，Q-D02）：单会话索引 + 撤销集，键带 auth: 服务前缀（12.3.2）；
 * 撤销集由网关每请求直查（即时失效 BR-006）。
 * 失败语义分级：登录路径 fail-open（互踢 / 索引 best-effort）；登出 / 重置撤销路径显式失败
 * （不得出现「响应成功但令牌仍可用」状态，FR-004 异常分支）。
 */
@Component
public class SessionRedis {

    private static final Logger log = LoggerFactory.getLogger(SessionRedis.class);

    /** 会话索引键前缀（单会话互踢索引；public 供集成测试装配/清理面使用）。 */
    public static final String SESSION_INDEX_PREFIX = "auth:session:user:";

    /** 撤销集键前缀（public 供集成测试断言/清理面使用）。 */
    public static final String REVOKE_KEY_PREFIX = "auth:revoke:";

    /**
     * 会话信息键（REQ-20261114-120105 delta1 定稿布局）：Redis Hash
     * {@code auth:session:info:{tokenId}}，字段 {@code ip}（登录来源 IP）与
     * {@code lastActiveAt}（epoch 毫秒字符串）。TTL=会话剩余有效期（auth 登录/续约/刷新时
     * 写入并续期）；权威最近活跃更新点=网关每请求 HSET（EXISTS 守卫，不建键不改 TTL）。
     */
    static final String SESSION_INFO_PREFIX = "auth:session:info:";
    static final String SESSION_INFO_FIELD_IP = "ip";
    static final String SESSION_INFO_FIELD_LAST_ACTIVE = "lastActiveAt";

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

    /**
     * 写会话信息哈希（REQ-20261114-120105：在线用户 ip/lastActiveAt 展示富化；TTL=会话剩余有效期）。
     * best-effort：写失败仅 WARN（展示字段缺失，不影响登录/续约/刷新主路径）。
     *
     * @param ip           登录来源 IP（可空）
     * @param lastActiveAt 最近活跃初值（登录=签发时点；续约/刷新=交互时点；权威更新=网关每请求）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void writeSessionInfo(String tokenId, String ip, Instant lastActiveAt, long ttlSeconds) {
        try {
            String key = SESSION_INFO_PREFIX + tokenId;
            Map fields = new HashMap<>();
            if (ip != null && !ip.isBlank()) {
                fields.put(SESSION_INFO_FIELD_IP, ip);
            }
            if (lastActiveAt != null) {
                fields.put(SESSION_INFO_FIELD_LAST_ACTIVE, Long.toString(lastActiveAt.toEpochMilli()));
            }
            if (fields.isEmpty()) {
                return;
            }
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, Duration.ofSeconds(Math.max(1, ttlSeconds)));
        } catch (RuntimeException ex) {
            log.warn("会话信息写入失败（在线用户展示字段缺失）：tokenId={}", tokenId);
        }
    }

    /** 读会话信息（缺失 / 异常 / 脏值返回 empty，展示层回退处理）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<SessionInfo> readSessionInfo(String tokenId) {
        try {
            Map entries = redis.opsForHash().entries(SESSION_INFO_PREFIX + tokenId);
            if (entries == null || entries.isEmpty()) {
                return Optional.empty();
            }
            String ip = (String) entries.get(SESSION_INFO_FIELD_IP);
            Object lastActive = entries.get(SESSION_INFO_FIELD_LAST_ACTIVE);
            Instant lastActiveAt = null;
            if (lastActive instanceof String value && !value.isBlank()) {
                lastActiveAt = Instant.ofEpochMilli(Long.parseLong(value));
            }
            return Optional.of(new SessionInfo(ip == null || ip.isBlank() ? null : ip, lastActiveAt));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** 会话信息快照（在线用户展示富化：登录 IP + 最近活跃时点）。 */
    public record SessionInfo(String ip, Instant lastActiveAt) {
    }

    /** 撤销写入失败承载（调用方映射 LOGOUT_FAILED / 系统错误）。 */
    public static final class RedisKeyWriteFailedException extends RuntimeException {

        public RedisKeyWriteFailedException(String tokenId, Throwable cause) {
            super("撤销集写入失败：" + tokenId, cause);
        }
    }
}
