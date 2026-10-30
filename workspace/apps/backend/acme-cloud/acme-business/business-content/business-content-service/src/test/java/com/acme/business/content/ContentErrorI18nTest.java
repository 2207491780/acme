package com.acme.business.content;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.PropertyResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误提示双语齐备性测试（架构设计 4.2 i18n 义务）：每错误码在 zh / en 两资源文件均有键（消息键=错误码）；
 * 语言包统一 _zh / _en 后缀（Modules.md v1.10）。
 */
class ContentErrorI18nTest {

    /** 跨域借码提示键（键全局稳定）：AuthorizationGateway 抛 platform-system 段权限码。 */
    private static final Set<String> SHARED_KEYS = Set.of("platform-system-07-0001");

    @Test
    void everyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/business-content-messages_zh.properties");
        var en = bundle("i18n/business-content-messages_en.properties");
        for (ContentError error : ContentError.values()) {
            assertThat(zh.containsKey(error.code())).as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code())).as("en 缺键：%s", error.code()).isTrue();
        }
        assertThat(zh.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
        assertThat(en.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
    }

    private static Set<String> expectedKeys() {
        Set<String> keys = new HashSet<>();
        for (ContentError error : ContentError.values()) {
            keys.add(error.code());
        }
        keys.addAll(SHARED_KEYS);
        return keys;
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = ContentErrorI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
