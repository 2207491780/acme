package com.acme.framework.i18n.support;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locale 解析测试（FR-006/AC-006、BR-011：覆盖头优先、非法回退 zh-CN 不抛出）。
 */
class I18nLocaleResolverTest {

    private final I18nLocaleResolver resolver = new I18nLocaleResolver("zh-CN", "en");

    @Test
    void overrideHeaderWins() {
        assertThat(resolver.resolve("en", "zh-CN")).isEqualTo(Locale.forLanguageTag("en"));
    }

    @Test
    void acceptLanguageUsedWhenNoOverride() {
        assertThat(resolver.resolve(null, "en-US,zh;q=0.9")).isEqualTo(Locale.forLanguageTag("en-US"));
    }

    @Test
    void missingOrInvalidFallsBackToDefault() {
        assertThat(resolver.resolve(null, null)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(resolver.resolve("!!invalid!!", " , ")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void underscoreLocaleNormalized() {
        assertThat(resolver.resolve("zh_TW", null)).isEqualTo(Locale.forLanguageTag("zh-TW"));
    }
}
