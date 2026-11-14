package com.acme.platform.system;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.PropertyResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261114-120102 新增错误码双语齐备性测试（契约变更清单：错误码
 * {@code NOTICE_PUBLISHED}/{@code NOTICE_NOT_FOUND}/{@code MESSAGE_FORBIDDEN}/{@code MESSAGE_NOT_FOUND}
 * 入错误码布局 + i18n zh/en；NOTICE_NOT_FOUND 由 TL 追认为第 4 枚）。
 * 口径照抄 {@link SystemErrorI18nTest}（消息键=错误码，语言包 _zh/_en 后缀；NFR-007 / Q-007 裁定 B）。
 */
class REQ20261114120102NotifyErrorI18nTest {

    private static final List<SystemError> NOTIFY_ERROR_CODES = List.of(
            SystemError.NOTICE_PUBLISHED, SystemError.NOTICE_NOT_FOUND,
            SystemError.MESSAGE_FORBIDDEN, SystemError.MESSAGE_NOT_FOUND);

    @Test
    void everyNotifyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/platform-system-messages_zh.properties");
        var en = bundle("i18n/platform-system-messages_en.properties");
        for (SystemError error : NOTIFY_ERROR_CODES) {
            assertThat(zh.containsKey(error.code()))
                    .as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code()))
                    .as("en 缺键：%s", error.code()).isTrue();
            assertThat(String.valueOf(zh.getObject(error.code())))
                    .as("zh 文案不应为空：%s", error.code()).isNotBlank();
            assertThat(String.valueOf(en.getObject(error.code())))
                    .as("en 文案不应为空：%s", error.code()).isNotBlank();
        }
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = REQ20261114120102NotifyErrorI18nTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
