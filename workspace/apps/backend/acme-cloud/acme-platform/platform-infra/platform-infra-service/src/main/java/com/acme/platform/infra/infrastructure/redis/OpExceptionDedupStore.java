package com.acme.platform.infra.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 运行异常日志去重键存储（REQ-20261117-061017 确认①，Redis SETNX TTL 口径，RedisDedupStore 风格）：
 * 键 {@code infra:op-exception:dedup:{service}:{type}:{pathHash}}（path 经 SHA-256 摘要收敛长度），
 * TTL 60s——同键异常风暴窗口内一次落库；Redis 不可达按「不去重」放行（fail-open：宁可多记不漏记）。
 */
@Component
public class OpExceptionDedupStore {

    /** 去重键前缀（{service} 服务名 / {type} 异常类型 / {pathHash} 请求路径 SHA-256 摘要）。 */
    public static final String KEY_PREFIX = "infra:op-exception:dedup:";

    /** 去重窗口（确认①：60s 一次）。 */
    public static final Duration DEDUP_TTL = Duration.ofSeconds(60);

    /** 占位值（仅作键存在性标记）。 */
    private static final String CLAIMED = "1";

    private final StringRedisTemplate redisTemplate;

    public OpExceptionDedupStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 认领（SETNX + TTL）：true=首次（应落库），false=窗口内重复（跳过）；
     * Redis 不可达按「不去重」放行（WARN 留痕）。
     */
    public boolean tryAcquire(String service, String exceptionType, String path) {
        try {
            Boolean claimed = redisTemplate.opsForValue()
                    .setIfAbsent(keyOf(service, exceptionType, path), CLAIMED, DEDUP_TTL);
            return !Boolean.FALSE.equals(claimed);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** 去重键（{service}:{type}:{pathHash}；path 经 SHA-256 收敛，空路径以空串参与）。 */
    static String keyOf(String service, String exceptionType, String path) {
        return KEY_PREFIX + safeSegment(service) + ":" + safeSegment(exceptionType) + ":" + sha256(path);
    }

    /** 键段净化：空白与分隔符收敛，保证键语义稳定。 */
    private static String safeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "-";
        }
        return segment.replace(':', '_').replace(' ', '_').trim();
    }

    /** 请求路径 SHA-256 摘要（hex 小写；null 等价空串）。 */
    static String sha256(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((path == null ? "" : path).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            // JVM 必有 SHA-256；防御分支按原文哈希退化（长度收敛失效可接受）
            return String.valueOf(path == null ? "" : path.hashCode());
        }
    }
}
