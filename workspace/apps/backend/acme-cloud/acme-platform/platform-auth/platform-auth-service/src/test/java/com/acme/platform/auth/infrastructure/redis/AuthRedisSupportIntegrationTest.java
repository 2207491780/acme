package com.acme.platform.auth.infrastructure.redis;

import com.acme.platform.auth.config.LoginGuardProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 支撑组件集成测试（Testcontainers Redis，16.2.1 本地可执行；无 Docker 自动跳过）：
 * 防爆破计数 / 锁定窗口、撤销集 TTL 写入与命中、写入失败语义。
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthRedisSupportIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void lockoutLifecycle() {
        LoginGuardProperties properties = new LoginGuardProperties();
        properties.setMaxFailures(3);
        properties.setWindowSeconds(60);
        properties.setLockSeconds(60);
        LoginGuardRedis guard = new LoginGuardRedis(redisTemplate, properties);

        assertThat(guard.isLocked("u-it")).isFalse();
        assertThat(guard.recordFailure("u-it")).isFalse();
        assertThat(guard.recordFailure("u-it")).isFalse();
        assertThat(guard.isLocked("u-it")).isFalse();
        // 第 3 次达阈值 → 锁定
        assertThat(guard.recordFailure("u-it")).isTrue();
        assertThat(guard.isLocked("u-it")).isTrue();
        // 计数已清（锁定标记独立存在）
        guard.clearFailures("u-it");
        assertThat(guard.isLocked("u-it")).isTrue();
        // 清锁（验证 TTL 写入语义后手动清理）
        redisTemplate.delete(LoginGuardRedis.LOCK_KEY_PREFIX + "u-it");
        assertThat(guard.isLocked("u-it")).isFalse();
    }

    @Test
    void revokeSetWriteAndHit() {
        SessionRedis sessionRedis = new SessionRedis(redisTemplate);
        assertThat(sessionRedis.isRevoked("jti-it")).isFalse();
        sessionRedis.revoke("jti-it", 60);
        assertThat(sessionRedis.isRevoked("jti-it")).isTrue();
        // TTL 生效（键带过期时间，自然回收）
        assertThat(redisTemplate.getExpire(SessionRedis.REVOKE_KEY_PREFIX + "jti-it")).isPositive();
        // 删除后恢复
        redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + "jti-it");
        assertThat(sessionRedis.isRevoked("jti-it")).isFalse();
    }

    @Test
    void sessionIndexLifecycle() {
        SessionRedis sessionRedis = new SessionRedis(redisTemplate);
        sessionRedis.indexSession(9001L, "jti-current", 60);
        assertThat(sessionRedis.currentSessionTokenId(9001L)).isEqualTo("jti-current");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "9001");
        assertThat(sessionRedis.currentSessionTokenId(9001L)).isNull();
    }

    @Test
    void revokeWriteFailureSemantics() {
        // 连接工厂关闭后写入 → 显式失败（登出 / 重置路径据此失败响应）
        LettuceConnectionFactory broken = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        broken.afterPropertiesSet();
        broken.start();
        StringRedisTemplate template = new StringRedisTemplate(broken);
        broken.destroy();
        SessionRedis sessionRedis = new SessionRedis(template);
        assertThatThrownBy(() -> sessionRedis.revoke("jti-x", 60))
                .isInstanceOf(RuntimeException.class);
    }
}
