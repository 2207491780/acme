package com.acme.framework.lock.config;

import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.lock.support.DistributedLocks;
import com.acme.framework.lock.support.LockFailurePolicy;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022、14.3.3 单一 Redis 配置源）。
 */
class LockSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LockAutoConfiguration.class))
            .withBean(KeyComposer.class, () -> new KeyComposer("switch-test"))
            .withBean(RedissonClient.class, () -> (RedissonClient) Proxy.newProxyInstance(
                    LockSwitchTest.class.getClassLoader(), new Class<?>[] {RedissonClient.class},
                    (p, method, args) -> {
                        throw new UnsupportedOperationException(method.getName());
                    }));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.lock.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DistributedLocks.class);
                    assertThat(context).doesNotHaveBean(LockProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            LockProperties properties = context.getBean(LockProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultPolicy()).isEqualTo(LockFailurePolicy.FAIL_FAST);
            assertThat(properties.getWaitMillis()).isEqualTo(1000);
            assertThat(properties.getWatchdogSeconds()).isEqualTo(30);
            assertThat(context).hasSingleBean(DistributedLocks.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.lock.default-policy=WAIT",
                        "acme.lock.wait-millis=2000",
                        "acme.lock.watchdog-seconds=15")
                .run(context -> {
                    LockProperties properties = context.getBean(LockProperties.class);
                    assertThat(properties.getDefaultPolicy()).isEqualTo(LockFailurePolicy.WAIT);
                    assertThat(properties.getWaitMillis()).isEqualTo(2000);
                    assertThat(properties.getWatchdogSeconds()).isEqualTo(15);
                });
    }
}
