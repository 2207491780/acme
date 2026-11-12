package com.acme.framework.idempotent.config;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.idempotent.aspect.IdempotentAspect;
import com.acme.framework.idempotent.internal.HttpIdempotentGate;
import com.acme.framework.idempotent.internal.IdempotentRecordStore;
import com.acme.framework.idempotent.internal.MqIdempotentGate;
import com.acme.framework.idempotent.support.InFlightPolicy;
import com.acme.framework.idempotent.support.MissingTokenPolicy;
import com.acme.framework.stream.support.IdempotentGuard;
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
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004、Q-D002/Q-006 默认值）。
 */
class IdempotentSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class))
            .withUserConfiguration(RedisStubs.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.idempotent.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotentAspect.class);
                    assertThat(context).doesNotHaveBean(HttpIdempotentGate.class);
                    assertThat(context).doesNotHaveBean(IdempotentRecordStore.class);
                    assertThat(context).doesNotHaveBean(IdempotentProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            IdempotentProperties properties = context.getBean(IdempotentProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getHeaderName()).isEqualTo("X-Acme-Idempotent-Token");
            assertThat(properties.getMissingTokenPolicy()).isEqualTo(MissingTokenPolicy.REJECT);
            assertThat(properties.getInFlightPolicy()).isEqualTo(InFlightPolicy.WAIT);
            assertThat(properties.getInFlightWait()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getClaimProcessingTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getResultTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(context).hasSingleBean(IdempotentAspect.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.idempotent.header-name=X-Custom",
                        "acme.idempotent.missing-token-policy=PASS",
                        "acme.idempotent.in-flight-policy=REJECT",
                        "acme.idempotent.in-flight-wait=1s",
                        "acme.idempotent.claim-processing-ttl=30s",
                        "acme.idempotent.result-ttl=1h")
                .run(context -> {
                    IdempotentProperties properties = context.getBean(IdempotentProperties.class);
                    assertThat(properties.getHeaderName()).isEqualTo("X-Custom");
                    assertThat(properties.getMissingTokenPolicy()).isEqualTo(MissingTokenPolicy.PASS);
                    assertThat(properties.getInFlightPolicy()).isEqualTo(InFlightPolicy.REJECT);
                    assertThat(properties.getInFlightWait()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getClaimProcessingTtl()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.getResultTtl()).isEqualTo(Duration.ofHours(1));
                });
    }

    @Test
    void mqGateWiredOnlyWhenStreamGuardPresent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MqIdempotentGate.class));
        runner.withBean(IdempotentGuard.class,
                        () -> new IdempotentGuard(null, new KeyComposer("t"), new com.acme.framework.stream.config.StreamProperties()))
                .run(context -> assertThat(context).hasSingleBean(MqIdempotentGate.class));
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
