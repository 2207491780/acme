package com.acme.framework.core.support;

import com.acme.common.context.Channel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流 key 构造测试（Modules.md 3.1.3、AC-017；p5-optional-modules D-001 自 framework-gateway 随迁，键格式不变）。
 */
class RateLimitKeyBuilderTest {

    private final DefaultRateLimitKeyBuilder keyBuilder = new DefaultRateLimitKeyBuilder();

    @Test
    void bKeyUsesAccountAndApi() {
        String key = keyBuilder.build(new RateLimitKeyInput("p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items"));
        assertThat(key).isEqualTo("p3-sample:B:u-1:/admin/sample/items");
    }

    @Test
    void cKeyUsesDeviceUserAndNormalizedApi() {
        String key = keyBuilder.build(new RateLimitKeyInput("p3-sample", Channel.C, "u-1", "dev-9", "u-1", "/app/sample/items/1"));
        assertThat(key).isEqualTo("p3-sample:C:dev-9:u-1:/app/sample/items/{id}");
    }

    @Test
    void missingDimensionsUsePlaceholder() {
        String key = keyBuilder.build(new RateLimitKeyInput(null, Channel.C, null, null, null, "/app/x"));
        assertThat(key).isEqualTo(":C:-:-:/app/x");
    }

    @Test
    void apiSegmentsNormalizeIdShapes() {
        assertThat(DefaultRateLimitKeyBuilder.normalizeApi("/orders/12345"))
                .isEqualTo("/orders/{id}");
        assertThat(DefaultRateLimitKeyBuilder.normalizeApi("/orders/550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo("/orders/{id}");
        assertThat(DefaultRateLimitKeyBuilder.normalizeApi("/orders/0123456789abcdef012"))
                .isEqualTo("/orders/{id}");
        assertThat(DefaultRateLimitKeyBuilder.normalizeApi("/orders/pending")).isEqualTo("/orders/pending");
    }

    @Test
    void resourceIdentifierGoesAsIs() {
        // 服务侧注解场景 api 段=业务声明的资源标识，原样入键（架构设计 11.3）
        assertThat(DefaultRateLimitKeyBuilder.normalizeApi("order.write")).isEqualTo("order.write");
        String key = keyBuilder.build(new RateLimitKeyInput("order-service", Channel.B, "acct-1", null, "u-1", "order.write"));
        assertThat(key).isEqualTo("order-service:B:acct-1:order.write");
    }

    @Test
    void fallbackKeyCarriesPrefixAndChannelTag() {
        String key = keyBuilder.buildFallback(new RateLimitFallbackInput(
                "p3-sample", "10.1.2.3", "/admin/sample/test-token"));
        assertThat(key).isEqualTo("p3-sample:FB:10.1.2.3:/admin/sample/test-token");
    }

    @Test
    void fallbackKeyUsesPlaceholderForMissingIp() {
        String key = keyBuilder.buildFallback(new RateLimitFallbackInput(
                "p3-sample", null, "/public/x"));
        assertThat(key).isEqualTo("p3-sample:FB:-:/public/x");
    }
}
