package com.acme.framework.core.support;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 键构造环境段（2026-11-18-02：单套中间件承载多环境）：acme.env-id 非空时 compose 输出前置环境段，
 * 空白保持历史格式逐字符一致（向后兼容）；含冒号 fail-fast 防键漂移。
 */
class KeyComposerTest {

    @Test
    void composeKeepsLegacyFormatWhenEnvIdBlank() {
        assertThat(new KeyComposer("platform-auth").compose("cache", "k1"))
                .isEqualTo("platform-auth:cache:k1");
        assertThat(new KeyComposer("platform-auth", " ").compose("c", "pg"))
                .isEqualTo("platform-auth:c:pg");
        assertThat(new KeyComposer("platform-auth", null).servicePrefix()).isEqualTo("platform-auth");
    }

    @Test
    void composePrefixesEnvSegmentWhenEnvIdSet() {
        KeyComposer composer = new KeyComposer("platform-system", "dev");
        assertThat(composer.envSegment()).isEqualTo("dev");
        assertThat(composer.compose("notify", "ws")).isEqualTo("dev:platform-system:notify:ws");
    }

    @Test
    void environmentConstructorResolvesEnvIdProperty() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.application.name", "platform-auth", "acme.env-id", "prod")));
        KeyComposer composer = new KeyComposer(environment);
        assertThat(composer.servicePrefix()).isEqualTo("platform-auth");
        assertThat(composer.envSegment()).isEqualTo("prod");
        assertThat(composer.compose("cache", "k")).isEqualTo("prod:platform-auth:cache:k");
    }

    @Test
    void environmentConstructorFallsBackToLegacyWhenEnvIdMissing() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.application.name", "platform-auth")));
        KeyComposer composer = new KeyComposer(environment);
        assertThat(composer.envSegment()).isEmpty();
        assertThat(composer.compose("k")).isEqualTo("platform-auth:k");
    }

    @Test
    void envIdWithColonFailsFast() {
        assertThatThrownBy(() -> new KeyComposer("s", "de:v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冒号");
    }
}
