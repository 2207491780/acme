package com.acme.platform.auth.infrastructure.redis;

import com.acme.platform.auth.config.LoginGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录防爆破 Redis 支撑（架构设计 4.2，Q-002 裁定）：失败计数 + 锁定标记，键带 auth: 服务前缀（12.3.2）。
 * Redis 不可用一律 fail-open（放行不计数、WARN 结构化日志，取舍 #7——与网关限流兜底口径一致）。
 * 计数与锁定非原子（INCR + EXPIRE / 达阈值置锁）：防爆破场景容忍竞态窗口，不做分布式锁。
 */
@Component
public class LoginGuardRedis {

    private static final Logger log = LoggerFactory.getLogger(LoginGuardRedis.class);

    static final String LOCK_KEY_PREFIX = "auth:lock:login:";
    static final String FAIL_KEY_PREFIX = "auth:fail:login:";

    private final StringRedisTemplate redis;
    private final LoginGuardProperties properties;

    public LoginGuardRedis(StringRedisTemplate redis, LoginGuardProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 锁定预检：锁定标记存在即拒绝；Redis 异常 fail-open。 */
    public boolean isLocked(String username) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY_PREFIX + username));
        } catch (RuntimeException ex) {
            log.warn("登录锁定预检失败，FAIL_OPEN 放行：username={}", username);
            return false;
        }
    }

    /**
     * 记录一次失败：窗口内计数自增，达阈值写锁定标记并清计数。
     *
     * @return true=本次达到阈值已锁定（调用方应返回 ACCOUNT_LOCKED）
     */
    public boolean recordFailure(String username) {
        try {
            String failKey = FAIL_KEY_PREFIX + username;
            Long count = redis.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redis.expire(failKey, Duration.ofSeconds(properties.getWindowSeconds()));
            }
            if (count != null && count >= properties.getMaxFailures()) {
                redis.opsForValue().set(LOCK_KEY_PREFIX + username, "1",
                        Duration.ofSeconds(properties.getLockSeconds()));
                redis.delete(failKey);
                return true;
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("登录失败计数失败，FAIL_OPEN 不计数：username={}", username);
            return false;
        }
    }

    /** 登录成功清计数（best-effort）。 */
    public void clearFailures(String username) {
        try {
            redis.delete(FAIL_KEY_PREFIX + username);
        } catch (RuntimeException ex) {
            log.warn("登录失败计数清理失败（不影响登录）：username={}", username);
        }
    }
}
