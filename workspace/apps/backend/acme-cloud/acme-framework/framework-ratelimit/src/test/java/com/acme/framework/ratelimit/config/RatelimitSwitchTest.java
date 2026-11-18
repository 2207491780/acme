package com.acme.framework.ratelimit.config;

import com.acme.framework.core.support.DefaultRateLimitKeyBuilder;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.ratelimit.aspect.RateLimitAspect;
import com.acme.framework.ratelimit.internal.TokenBucket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004）。
 */
class RatelimitSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RatelimitAutoConfiguration.class))
            .withUserConfiguration(RedisStubs.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.ratelimit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RateLimitAspect.class);
                    assertThat(context).doesNotHaveBean(TokenBucket.class);
                    assertThat(context).doesNotHaveBean(RatelimitProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            RatelimitProperties properties = context.getBean(RatelimitProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultCapacity()).isEqualTo(100);
            assertThat(properties.getDefaultRefillPermitsPerSecond()).isEqualTo(10);
            assertThat(properties.getOnError()).isEqualTo(RateLimitOnError.FAIL_OPEN);
            assertThat(properties.getRedisTimeout()).isEqualTo(Duration.ofMillis(100));
            assertThat(context).hasSingleBean(RateLimitAspect.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.ratelimit.default-capacity=5",
                        "acme.ratelimit.default-refill-permits-per-second=2.5",
                        "acme.ratelimit.on-error=FAIL_CLOSE",
                        "acme.ratelimit.redis-timeout=50ms")
                .run(context -> {
                    RatelimitProperties properties = context.getBean(RatelimitProperties.class);
                    assertThat(properties.getDefaultCapacity()).isEqualTo(5);
                    assertThat(properties.getDefaultRefillPermitsPerSecond()).isEqualTo(2.5);
                    assertThat(properties.getOnError()).isEqualTo(RateLimitOnError.FAIL_CLOSE);
                    assertThat(properties.getRedisTimeout()).isEqualTo(Duration.ofMillis(50));
                });
    }

    @Test
    void keyBuilderSharedWithGatewayViaConditionalOnMissingBean() {
        runner.run(context -> {
            assertThat(context.getBean(RateLimitKeyBuilder.class))
                    .isInstanceOf(DefaultRateLimitKeyBuilder.class);
        });
    }

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
    }
}
