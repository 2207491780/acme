package com.acme.framework.cache.interceptor;

import com.acme.framework.cache.internal.CaffeineL1Cache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 失效广播（FR-006/AC-006：订阅清除本实例；跳过自身防回环；广播失败兜底清本地）。
 */
class L1InvalidationBusTest {

    private static final char SEP = 1;

    @Test
    void channelCarriesServicePrefix() {
        assertThat(L1InvalidationBus.channelOf("order-service"))
                .isEqualTo("acme:cache:invalidate:order-service");
    }

    @Test
    void subscriptionClearsLocalL1() {
        CaffeineL1Cache l1 = new CaffeineL1Cache(10, 60);
        l1.put("k", "v");
        L1InvalidationBus bus = new L1InvalidationBus(null, "ch", l1, "self-1");
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                ("other-1" + SEP + "k").getBytes(StandardCharsets.UTF_8)), null);
        assertThat(l1.getIfPresent("k")).isNull();
    }

    @Test
    void skipsOwnBroadcastToAvoidLoopback() {
        CaffeineL1Cache l1 = new CaffeineL1Cache(10, 60);
        l1.put("k", "v");
        L1InvalidationBus bus = new L1InvalidationBus(null, "ch", l1, "self-1");
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                ("self-1" + SEP + "k").getBytes(StandardCharsets.UTF_8)), null);
        assertThat(l1.getIfPresent("k")).as("跳过自身广播（防回环清掉本实例 L1）").isEqualTo("v");
    }

    @Test
    void legacyBareKeyStillApplied() {
        CaffeineL1Cache l1 = new CaffeineL1Cache(10, 60);
        l1.put("k", "v");
        L1InvalidationBus bus = new L1InvalidationBus(null, "ch", l1, "self-1");
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                "k".getBytes(StandardCharsets.UTF_8)), null);
        assertThat(l1.getIfPresent("k")).isNull();
    }

    @Test
    void publishFailureFallsBackToLocalClear() {
        CaffeineL1Cache l1 = new CaffeineL1Cache(10, 60);
        l1.put("k", "v");
        L1InvalidationBus bus = new L1InvalidationBus(
                new org.springframework.data.redis.core.StringRedisTemplate(), "ch", l1, "self-1");
        bus.publishEvict("k");
        assertThat(l1.getIfPresent("k")).isNull();
    }
}
