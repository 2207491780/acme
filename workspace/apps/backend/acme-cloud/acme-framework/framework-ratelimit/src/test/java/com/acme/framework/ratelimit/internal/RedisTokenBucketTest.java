package com.acme.framework.ratelimit.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 令牌桶集成测试（FR-016、AC-016：配额内放行、超配额拒绝、跨连接共享计数；
 * Testcontainers 16.2.1 不依赖本地环境——无 Docker 自动跳过，与 platform 侧集成测试同口径）。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    private static RedisTokenBucket bucket;
    private static StringRedisTemplate template;

    @BeforeAll
    static void initBucket() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        bucket = new RedisTokenBucket(template, Duration.ofMillis(500));
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
