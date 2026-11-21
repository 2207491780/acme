package com.acme.platform.workflow;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.PropertyResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误提示双语齐备性测试（NFR-007 / Q-007 裁定 B；BPM-1 round-1 修复单⑩授权交付）：
 * WorkflowError 每错误码在 zh / en 两资源文件均有键（消息键=错误码，架构设计 8.2）；
 * 键集与枚举一一对应（防漂移——平台-workflow-01/02/03 三段 14 码）。
 */
class WorkflowErrorI18nTest {

    @Test
    void everyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/platform-workflow-messages_zh.properties");
        var en = bundle("i18n/platform-workflow-messages_en.properties");
        for (WorkflowError error : WorkflowError.values()) {
            assertThat(zh.containsKey(error.code()))
                    .as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code()))
                    .as("en 缺键：%s", error.code()).isTrue();
        }
        // 键集与枚举一一对应（防漂移）
        assertThat(zh.keySet()).hasSize(WorkflowError.values().length);
        assertThat(en.keySet()).hasSize(WorkflowError.values().length);
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = WorkflowErrorI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
