package com.acme.platform.auth;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.PropertyResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误提示双语齐备性测试（NFR-007 / Q-007 裁定 B）：每错误码枚举在 zh / en 两资源文件均有键
 * （消息键=错误码）；语言包统一 _zh 后缀（zh_CN 与裸 zh 查询均命中）。
 */
class AuthErrorI18nTest {

    /** 跨域借码提示键（键全局稳定，MessageKeys 口径）：AuthorizationGateway 抛 platform-system 段权限码。 */
    private static final Set<String> SHARED_KEYS = Set.of("platform-system-07-0001");

    @Test
    void everyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/platform-auth-messages_zh.properties");
        var en = bundle("i18n/platform-auth-messages_en.properties");
        for (AuthError error : AuthError.values()) {
            assertThat(zh.containsKey(error.code())).as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code())).as("en 缺键：%s", error.code()).isTrue();
        }
        // 键集与「枚举 + 跨域借码」一一对应（防漂移）
        assertThat(zh.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
        assertThat(en.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
    }

    private static Set<String> expectedKeys() {
        Set<String> keys = new HashSet<>();
        for (AuthError error : AuthError.values()) {
            keys.add(error.code());
        }
        keys.addAll(SHARED_KEYS);
        return keys;
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = AuthErrorI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
