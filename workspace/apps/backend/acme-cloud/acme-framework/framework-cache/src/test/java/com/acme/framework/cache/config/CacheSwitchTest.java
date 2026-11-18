package com.acme.framework.cache.config;

import com.acme.framework.cache.internal.CaffeineL1Cache;
import com.acme.framework.cache.interceptor.L1InvalidationBus;
import com.acme.framework.cache.interceptor.PenetrationGuard;
import com.acme.framework.cache.support.CacheKeys;
import com.acme.framework.cache.support.TwoLevelCache;
import com.acme.framework.core.support.KeyComposer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022）。
 */
class CacheSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
            .withUserConfiguration(RedisStubs.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.cache.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TwoLevelCache.class);
                    assertThat(context).doesNotHaveBean(CaffeineL1Cache.class);
                    assertThat(context).doesNotHaveBean(L1InvalidationBus.class);
                    assertThat(context).doesNotHaveBean(PenetrationGuard.class);
                    assertThat(context).doesNotHaveBean(CacheKeys.class);
                    assertThat(context).doesNotHaveBean(CacheProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            CacheProperties properties = context.getBean(CacheProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getL1().getMaxSize()).isEqualTo(10000);
            assertThat(properties.getL1().getTtlSeconds()).isEqualTo(60);
            assertThat(properties.getL2().getTtlSeconds()).isEqualTo(1800);
            assertThat(properties.getL2Failure()).isEqualTo(L2FailurePolicy.DEGRADE);
            assertThat(properties.getPenetrationGuard().isEnabled()).isTrue();
            assertThat(properties.getPenetrationGuard().getLeaseMillis()).isEqualTo(3000);
            assertThat(properties.getPenetrationGuard().getWaitMillis()).isEqualTo(200);
            assertThat(context).hasSingleBean(TwoLevelCache.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.cache.l1.max-size=10",
                        "acme.cache.l1.ttl-seconds=5",
                        "acme.cache.l2.ttl-seconds=30",
                        "acme.cache.l2-failure=FAIL",
                        "acme.cache.penetration-guard.enabled=false",
                        "acme.cache.penetration-guard.lease-millis=100",
                        "acme.cache.penetration-guard.wait-millis=50")
                .run(context -> {
                    CacheProperties properties = context.getBean(CacheProperties.class);
                    assertThat(properties.getL1().getMaxSize()).isEqualTo(10);
                    assertThat(properties.getL1().getTtlSeconds()).isEqualTo(5);
                    assertThat(properties.getL2().getTtlSeconds()).isEqualTo(30);
                    assertThat(properties.getL2Failure()).isEqualTo(L2FailurePolicy.FAIL);
                    assertThat(properties.getPenetrationGuard().isEnabled()).isFalse();
                    assertThat(properties.getPenetrationGuard().getLeaseMillis()).isEqualTo(100);
                    assertThat(properties.getPenetrationGuard().getWaitMillis()).isEqualTo(50);
                });
    }

    /** Redis 依赖桩件（开关测试不触达真实 Redis；订阅容器以同名桩件替代启动行为）。 */
    @Configuration(proxyBeanMethods = false)
    static class RedisStubs {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return (RedisConnectionFactory) java.lang.reflect.Proxy.newProxyInstance(
                    RedisStubs.class.getClassLoader(), new Class<?>[] {RedisConnectionFactory.class},
                    (p, method, args) -> {
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean("cacheInvalidationListenerContainer")
        RedisMessageListenerContainer cacheInvalidationListenerContainer() {
            return new RedisMessageListenerContainer() {
                @Override
                public void afterPropertiesSet() {
                    // 开关测试不接线真实 Redis
                }

                @Override
                public void start() {
                    // 开关测试不订阅真实 Redis
                }
            };
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
