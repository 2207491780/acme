package com.acme.framework.cache.support;

import com.acme.framework.core.support.KeyComposer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缓存 key 规范（FR-008/AC-008、BR-008：服务前缀 12.3.2）。
 */
class CacheKeysTest {

    private final CacheKeys keys = new CacheKeys(new KeyComposer("order-service"));

    @Test
    void keyCarriesServicePrefixAndNamespace() {
        assertThat(keys.of("item", "1001")).isEqualTo("order-service:c:item:1001");
    }

    @Test
    void keySupportsMultiSegmentBusinessParts() {
        assertThat(keys.of("user", "u-1", "profile")).isEqualTo("order-service:c:user:u-1:profile");
    }

    @Test
    void blankSegmentFailsExplicitly() {
        assertThatThrownBy(() -> keys.of("ns", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void servicePrefixExposed() {
        assertThat(keys.servicePrefix()).isEqualTo("order-service");
    }
}
