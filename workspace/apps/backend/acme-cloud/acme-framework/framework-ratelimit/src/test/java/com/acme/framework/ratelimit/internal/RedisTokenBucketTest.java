package com.acme.framework.ratelimit.internal;

import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 令牌桶集成测试（FR-016、AC-016：配额内放行、超配额拒绝、跨连接共享计数；
 * 零 Docker 嵌入式 Redis，本地与 CI 均无需 Docker 即可稳定执行）。
 */
class RedisTokenBucketTest {

    private static final EmbeddedRedisSupport REDIS = EmbeddedRedisSupport.shared();

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTokenBucket bucket;
    private static StringRedisTemplate template;

    @BeforeAll
    static void initBucket() {
        REDIS.flushAll();
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.host(), REDIS.port()));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        bucket = new RedisTokenBucket(template, Duration.ofMillis(500));
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void grantsUpToCapacityThenRejects() {
        String key = "ratelimit-test:B:acct:/api";
        template.delete(key);
        assertThat(bucket.tryAcquire(key, 2, 0.001)).isTrue();
        assertThat(bucket.tryAcquire(key, 2, 0.001)).isTrue();
        assertThat(bucket.tryAcquire(key, 2, 0.001)).isFalse();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        String key = "ratelimit-test:refill";
        template.delete(key);
        assertThat(bucket.tryAcquire(key, 1, 5)).isTrue();
        assertThat(bucket.tryAcquire(key, 1, 5)).isFalse();
        Thread.sleep(300);
        assertThat(bucket.tryAcquire(key, 1, 5)).isTrue();
    }

    @Test
    void sharedCounterAcrossClients() {
        String key = "ratelimit-test:shared";
        template.delete(key);
        RedisTokenBucket other = new RedisTokenBucket(template, Duration.ofMillis(500));
        assertThat(bucket.tryAcquire(key, 3, 0.001)).isTrue();
        assertThat(other.tryAcquire(key, 3, 0.001)).isTrue();
        assertThat(bucket.tryAcquire(key, 3, 0.001)).isTrue();
        // 第四个请求超配额：跨实例共享同一计数（AC-016）
        assertThat(other.tryAcquire(key, 3, 0.001)).isFalse();
    }
}
