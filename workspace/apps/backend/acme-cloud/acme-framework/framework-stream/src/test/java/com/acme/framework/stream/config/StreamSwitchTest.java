package com.acme.framework.stream.config;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.stream.internal.DedupStore;
import com.acme.framework.stream.interceptor.ConsumeChannelInterceptor;
import com.acme.framework.stream.interceptor.ConsumePipeline;
import com.acme.framework.stream.support.IdempotentGuard;
import com.acme.framework.stream.support.MessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022）。
 */
class StreamSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StreamAutoConfiguration.class))
            .withUserConfiguration(Stubs.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.stream.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MessageSender.class);
                    assertThat(context).doesNotHaveBean(IdempotentGuard.class);
                    assertThat(context).doesNotHaveBean(ConsumeChannelInterceptor.class);
                    assertThat(context).doesNotHaveBean(DedupStore.class);
                    assertThat(context).doesNotHaveBean(StreamProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            StreamProperties properties = context.getBean(StreamProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getIdempotency().getTtlHours()).isEqualTo(72);
            assertThat(properties.getIdempotency().getProcessingTtl()).isEqualTo(java.time.Duration.ofMinutes(10));
            assertThat(properties.getIdempotency().getMissingKeyPolicy())
                    .isEqualTo(MissingIdempotentKeyPolicy.REJECT);
            assertThat(properties.getDlq().isRequired()).isTrue();
            assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(3);
            assertThat(properties.getRetry().getBackoffInitialMillis()).isEqualTo(100);
            assertThat(properties.getRetry().getBackoffMultiplier()).isEqualTo(2);
            assertThat(properties.getRetry().getBackoffMaxMillis()).isEqualTo(2000);
            assertThat(context).hasSingleBean(MessageSender.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.stream.idempotency.ttl-hours=24",
                        "acme.stream.idempotency.processing-ttl=PT5M",
                        "acme.stream.idempotency.missing-key-policy=ACCEPT_WITH_WARN",
                        "acme.stream.dlq.required=false",
                        "acme.stream.retry.max-attempts=5")
                .run(context -> {
                    StreamProperties properties = context.getBean(StreamProperties.class);
                    assertThat(properties.getIdempotency().getTtlHours()).isEqualTo(24);
                    assertThat(properties.getIdempotency().getProcessingTtl())
                            .isEqualTo(java.time.Duration.ofMinutes(5));
                    assertThat(properties.getIdempotency().getMissingKeyPolicy())
                            .isEqualTo(MissingIdempotentKeyPolicy.ACCEPT_WITH_WARN);
                    assertThat(properties.getDlq().isRequired()).isFalse();
                    assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
                });
    }

    /** 依赖桩件（开关测试不触达真实 Redis；StreamBridge 经延迟供给不落桩）。 */
    @Configuration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory() {
            return (org.springframework.data.redis.connection.RedisConnectionFactory)
                    java.lang.reflect.Proxy.newProxyInstance(
                            Stubs.class.getClassLoader(),
                            new Class<?>[] {org.springframework.data.redis.connection.RedisConnectionFactory.class},
                            (p, method, args) -> {
                                throw new UnsupportedOperationException(method.getName());
                            });
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(
                org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }
}
