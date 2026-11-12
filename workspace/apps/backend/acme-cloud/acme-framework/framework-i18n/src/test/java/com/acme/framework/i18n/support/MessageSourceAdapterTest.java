package com.acme.framework.i18n.support;

import com.acme.framework.i18n.config.I18nProperties;
import com.acme.framework.i18n.internal.MessageSourceAssembler;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MessageSource 装配测试（FR-007/AC-007/031、DP-012 定稿口径：缺 key RETURN_KEY、zh/en 回退链，Q-009）。
 */
class MessageSourceAdapterTest {

    private final MessageSourceAdapter adapter =
            new MessageSourceAdapter(MessageSourceAssembler.assemble(new I18nProperties()), Locale.ENGLISH,
                    I18nProperties.MissingKeyPolicy.RETURN_KEY);

    @Test
    void resolvesZhAndEnResources() {
        assertThat(adapter.getMessage("SYS-17-0001", Locale.SIMPLIFIED_CHINESE)).contains("频繁");
        assertThat(adapter.getMessage("SYS-17-0001", Locale.ENGLISH)).contains("requests");
    }

    @Test
    void zhCnAndGenericZhBothResolveZhResource() {
        // 语言包统一 _zh 后缀：zh_CN 与裸 zh 查询均命中简中资源（Q-007 裁定 B 命名口径）
        assertThat(adapter.getMessage("SYS-17-0001", Locale.forLanguageTag("zh-CN"))).contains("频繁");
        assertThat(adapter.getMessage("SYS-17-0001", Locale.forLanguageTag("zh"))).contains("频繁");
    }

    @Test
    void missingKeyReturnsKeyItself() {
        assertThat(adapter.getMessage("no.such.key", Locale.ENGLISH)).isEqualTo("no.such.key");
    }

    @Test
    void missingInRequestedLocaleFallsBackToEn() {
        // fr 资源不存在 → 回退 en（Q-009 回退链）
        assertThat(adapter.getMessage("SYS-17-0001", Locale.FRENCH)).contains("requests");
    }

    @Test
    void throwPolicyFailsStrictly() {
        MessageSourceAdapter strict = new MessageSourceAdapter(
                MessageSourceAssembler.assemble(new I18nProperties()), Locale.ENGLISH,
                I18nProperties.MissingKeyPolicy.THROW);
        assertThatThrownBy(() -> strict.getMessage("no.such.key", Locale.ENGLISH))
                .isInstanceOf(RuntimeException.class);
    }
}
