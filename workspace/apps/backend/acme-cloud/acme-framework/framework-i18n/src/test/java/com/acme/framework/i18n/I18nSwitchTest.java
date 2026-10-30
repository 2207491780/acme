package com.acme.framework.i18n;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.i18n.config.I18nAutoConfiguration;
import com.acme.framework.i18n.config.I18nProperties;
import com.acme.framework.i18n.interceptor.LocaleInterceptor;
import com.acme.framework.i18n.support.I18nLocaleResolver;
import com.acme.framework.i18n.support.MessageSourceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关、配置缺省与 PromptLocalizer 出口测试（FR-024/AC-024/031、D-006、BR-003/004、Q-009）。
 */
class I18nSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(I18nAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.i18n.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(I18nLocaleResolver.class);
                    assertThat(context).doesNotHaveBean(MessageSourceAdapter.class);
                    assertThat(context).doesNotHaveBean(PromptLocalizer.class);
                    assertThat(context).doesNotHaveBean(LocaleInterceptor.class);
                    assertThat(context).doesNotHaveBean(I18nProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            I18nProperties properties = context.getBean(I18nProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultLocale()).isEqualTo("zh-CN");
            assertThat(properties.getFallbackLocale()).isEqualTo("en");
            assertThat(properties.getHeaderName()).isEqualTo("Accept-Language");
            assertThat(properties.getOverrideHeader()).isEqualTo("X-Acme-Language");
            assertThat(properties.getBasenames()).containsExactly("i18n/acme-messages");
            assertThat(properties.getMissingKey()).isEqualTo(I18nProperties.MissingKeyPolicy.RETURN_KEY);
            assertThat(context).hasSingleBean(PromptLocalizer.class);
        });
    }

    @Test
    void promptLocalizerReturnsNullWhenKeyMissing() {
        runner.run(context -> {
            PromptLocalizer localizer = context.getBean(PromptLocalizer.class);
            ErrorCode known = codeOf("SYS-17-0001");
            ErrorCode unknown = codeOf("SYS-99-9999");
            assertThat(localizer.localize(known, Locale.ENGLISH)).contains("requests");
            assertThat(localizer.localize(unknown, Locale.ENGLISH)).isNull();
        });
    }

    private static ErrorCode codeOf(String code) {
        return new ErrorCode() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String defaultMessage() {
                return "default";
            }
        };
    }
}
