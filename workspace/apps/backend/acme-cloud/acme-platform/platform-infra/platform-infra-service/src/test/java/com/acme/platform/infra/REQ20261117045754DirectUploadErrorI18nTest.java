package com.acme.platform.infra;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261117-045754 直传错误码提示双语齐备性测试（AC-10 后端可断言部分：i18n properties 成对；
 * AC-10 前端 UploadButton/MSW 部分由前端代理测试覆盖）。沿用 {@code InfraErrorI18nTest} 口径：
 * 键=错误码，zh / en 资源文件均含键（语言包统一 _zh/_en 后缀）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link #everyDirectUploadErrorCodeHasPairedZhAndEnMessages}
 *       —— 直传新错误码族（wire 字面量）在 platform-infra-messages zh / en 成对且非空。</li>
 *   <li>{@link #storageErrorSys15ContinuationKeysStayPairedAcrossZhAndEn}
 *       —— framework 侧 StorageError SYS-15 段续编（契约变更清单：QUOTA_EXCEEDED/IMAGE_INVALID/
 *       TICKET_INVALID/PRESIGN_UNSUPPORTED 族码）在 acme-messages zh / en 键集成对且在既有 0006
 *       基线之上追加（段位号实现自选，故做结构断言而非精确段位）。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义就地标注 AMB，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>AMB-1 错误码 wire 字面量取委派单口径（与
 *       {@code REQ20261117045754DirectUploadIntegrationTest} 的 {@code CODE_*} 常量一致）；
 *       契约 API 节用无前缀 QUOTA_EXCEEDED、确认记录③④用 STORAGE_ 前缀：三向核对后统一改常量。</li>
 *   <li>平台侧新码登记位置假定 = platform-infra-messages（直传端点属 platform-infra，键=错误码）；
 *       若实现经 InfraError 枚举落码，既有 {@code InfraErrorI18nTest} 结构断言会兜底覆盖。</li>
 *   <li>acme-messages 经 framework-i18n 依赖在 platform-infra 测试类路径可见（acme.i18n.basenames
 *       引用 i18n/acme-messages）。</li>
 * </ul>
 */
class REQ20261117045754DirectUploadErrorI18nTest {

    /** 直传新错误码族（AMB-1：委派单口径；STORAGE_OBJECT_MISSING 为契约 API 节明示）。 */
    private static final List<String> DIRECT_UPLOAD_WIRE_CODES = List.of(
            "STORAGE_QUOTA_EXCEEDED", "IMAGE_INVALID", "TICKET_INVALID", "SIZE_MISMATCH",
            "STORAGE_OBJECT_MISSING");

    /** 既有 StorageError SYS-15 段基线段位（SYS-15-0001..0006，续编在此之上）。 */
    private static final int SYS15_BASELINE_COUNT = 6;

    @Test
    void everyDirectUploadErrorCodeHasPairedZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/platform-infra-messages_zh.properties");
        var en = bundle("i18n/platform-infra-messages_en.properties");
        for (String code : DIRECT_UPLOAD_WIRE_CODES) {
            assertThat(zh.containsKey(code)).as("zh 缺键：%s", code).isTrue();
            assertThat(en.containsKey(code)).as("en 缺键：%s", code).isTrue();
            assertThat(zh.getString(code).isBlank()).as("zh 空文案：%s", code).isFalse();
            assertThat(en.getString(code).isBlank()).as("en 空文案：%s", code).isFalse();
        }
    }

    @Test
    void storageErrorSys15ContinuationKeysStayPairedAcrossZhAndEn() throws Exception {
        var zh = bundle("i18n/acme-messages_zh.properties");
        var en = bundle("i18n/acme-messages_en.properties");

        Set<String> zhSys15 = keysWithPrefix(zh, "SYS-15-");
        Set<String> enSys15 = keysWithPrefix(en, "SYS-15-");
        assertThat(zhSys15).as("SYS-15 段 zh/en 键集应成对").isEqualTo(enSys15);
        assertThat(zhSys15.size())
                .as("SYS-15 段续编：直传族码（PRESIGN_UNSUPPORTED 等）应在 0006 基线之上成对追加")
                .isGreaterThan(SYS15_BASELINE_COUNT);
    }

    private static Set<String> keysWithPrefix(PropertyResourceBundle bundle, String prefix) {
        Set<String> keys = new HashSet<>();
        for (String key : bundle.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = REQ20261117045754DirectUploadErrorI18nTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
