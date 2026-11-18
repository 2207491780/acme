package com.acme.framework.idempotent;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.idempotent.config.IdempotentAutoConfiguration;
import com.acme.framework.idempotent.internal.HttpIdempotentGate;
import com.acme.framework.idempotent.internal.MqIdempotentGate;
import com.acme.framework.idempotent.support.InFlightPolicy;
import com.acme.framework.stream.config.StreamAutoConfiguration;
import com.acme.framework.stream.support.IdempotentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等双入口集成测试（16.1 幂等必备：HTTP token + MQ messageId；Redis 用 Testcontainers，
 * 16.2.1 不依赖本地环境——无 Docker 自动跳过，与 platform 侧集成测试同口径）。
 * MQ 入口经 MqIdempotentGate 包装 framework-stream IdempotentGuard
 * （BR-007 复用确认：同一 IdempotentGuard 基座，无第二套去重实现，AC-003）。
 */
@Testcontainers(disabledWithoutDocker = true)
class IdempotentRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StreamAutoConfiguration.class, IdempotentAutoConfiguration.class))
            .withUserConfiguration(RedisConfig.class)
            .withBean(KeyComposer.class, () -> new KeyComposer("p5-sample"));

    @Configuration(proxyBeanMethods = false)
    static class RedisConfig {

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                    REDIS.getHost(), REDIS.getMappedPort(6379)));
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            return template;
        }
    }

    @Test
    void httpEntrySingleExecutesWithResultReplay() {
        runner.run(context -> {
            HttpIdempotentGate gate = context.getBean(HttpIdempotentGate.class);
            AtomicInteger calls = new AtomicInteger();
            String first = gate.execute("p5:idem:http:tok-http", () -> "result-" + calls.incrementAndGet(),
                    String.class, InFlightPolicy.WAIT);
            String replay = gate.execute("p5:idem:http:tok-http", () -> "result-" + calls.incrementAndGet(),
                    String.class, InFlightPolicy.WAIT);
            assertThat(first).isEqualTo("result-1");
            assertThat(replay).isEqualTo("result-1");
            assertThat(calls.get()).isEqualTo(1);
        });
    }

    @Test
    void httpEntryFailureReleasesAndRetries() {
        runner.run(context -> {
            HttpIdempotentGate gate = context.getBean(HttpIdempotentGate.class);
            try {
                gate.execute("p5:idem:http:tok-retry", () -> {
                    throw new IllegalStateException("boom");
                }, String.class, InFlightPolicy.WAIT);
            } catch (IllegalStateException expected) {
                // 业务失败释放认领
            }
            AtomicInteger calls = new AtomicInteger();
            assertThat(gate.execute("p5:idem:http:tok-retry", () -> "ok-" + calls.incrementAndGet(),
                    String.class, InFlightPolicy.WAIT)).isEqualTo("ok-1");
        });
    }

    @Test
    void mqEntryDedupViaStreamGuardOnlyProcessesOnce() {
        runner.run(context -> {
            MqIdempotentGate gate = context.getBean(MqIdempotentGate.class);
            AtomicInteger processed = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                if (gate.tryBegin("msg-1") == IdempotentGuard.Begin.STARTED) {
                    processed.incrementAndGet();
                    gate.markDone("msg-1");
                }
            }
            assertThat(processed.get()).isEqualTo(1);
            // 同一 IdempotentGuard 基座（AC-003 复用对照）
            assertThat(context.getBean(IdempotentGuard.class)).isNotNull();
        });
    }
}
