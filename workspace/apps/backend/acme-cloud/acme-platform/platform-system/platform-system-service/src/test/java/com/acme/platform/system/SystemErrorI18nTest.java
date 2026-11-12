package com.acme.platform.system;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.PropertyResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误提示双语齐备性测试（NFR-007 / Q-007 裁定 B）：每错误码枚举在 zh / en 两资源文件均有键
 * （消息键=错误码，架构设计 8.2）；语言包统一 _zh 后缀（zh_CN 与裸 zh 查询均命中）。
 */
class SystemErrorI18nTest {

    @Test
    void everyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/platform-system-messages_zh.properties");
        var en = bundle("i18n/platform-system-messages_en.properties");
        for (SystemError error : SystemError.values()) {
            assertThat(zh.containsKey(error.code()))
                    .as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code()))
                    .as("en 缺键：%s", error.code()).isTrue();
        }
        // 键集与枚举一一对应（防漂移）
        assertThat(zh.keySet()).hasSize(SystemError.values().length);
        assertThat(en.keySet()).hasSize(SystemError.values().length);
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = SystemErrorI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
