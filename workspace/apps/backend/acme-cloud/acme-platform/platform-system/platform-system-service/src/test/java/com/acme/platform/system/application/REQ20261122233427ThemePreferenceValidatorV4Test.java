package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ThemePreferenceValidator} schema v4 校验面用例（REQ-20261122-233427 确认记录②/⑧ + AC-8）。
 *
 * 表面（契约钉死，勿臆造）：
 *   - v4 新字段：layoutMode('sidebar'|'top'|'mixed') / tabStyle('line'|'card'|'chrome'|'plain') /
 *     siderTone('auto'|'dark') / topbarWidgets(Record&lt;WidgetKey,boolean&gt;，
 *     WidgetKey 枚举=契约②原文 search/bell/lang/timezone/appearance/themeToggle/lock/
 *     fullscreen/settings/release；release 键按 REQ-20261126-125836 保留为 legacy-tolerant，
 *     仍接受、行为不变）；
 *   - v4 合法载荷通过；非法值/未知键拒绝；v3 旧载荷（schemaVersion=3 + v3 字段集）兼容通过；
 *   - 业务错误口径 = 统一响应体 {@code Result.code≠0}（BizException → 错误码段 SYS-01-xxxx，
 *     断言沿用既有 ThemePreferenceValidatorTest 口径；HTTP 层非 4xx 断言面）。
 *
 * 装配：纯 JUnit 无 Spring（既有 ThemePreferenceValidatorTest 同款；端到端两域语义由
 * REQ20261121200415PreferenceTwoDomainIntegrationTest 承载，本文件不重复）。
 * 阶段状态：validator v4 扩展交付前，v4 正/负例断言红（等实现）；v3 兼容面现在即绿。
 */
class REQ20261122233427ThemePreferenceValidatorV4Test {

    /** WidgetKey 枚举 = 契约②原文逐字（十键；release 为 legacy-tolerant 保留键，行为不变）。 */
    private static final String[] WIDGET_KEYS = {
            "search", "bell", "lang", "timezone", "appearance",
            "themeToggle", "lock", "fullscreen", "settings", "release",
    };

    // ---------- v4 合法载荷 ----------

    @Test
    void should_pass_when_v4_payload_with_all_new_fields_valid() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 4,
                "preferences", fullV4Preferences("top", "chrome", "dark"))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_v4_payload_subset_fields() {
        // v4 载荷可只携带部分新字段（逐键白名单子集合法）
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("mode", "dark");
        preferences.put("layoutMode", "mixed");
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 4, "preferences", preferences)))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_v3_legacy_payload_still_accepted() {
        // v3 旧载荷兼容：schemaVersion=3 + v3 字段集（无 v4 字段）→ 通过（AC-8 兼容面）
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("mode", "dark");
        preferences.put("accent", "cyan");
        preferences.put("customAccent", "#5aa7a0");
        preferences.put("density", "comfortable");
        preferences.put("motionEnabled", true);
        preferences.put("radius", "medium");
        preferences.put("grayMode", false);
        preferences.put("weakMode", false);
        preferences.put("contentWidth", "fluid");
        Map<String, Object> semanticColors = new LinkedHashMap<>();
        semanticColors.put("success", null);
        semanticColors.put("warning", "#ff5a5f");
        semanticColors.put("danger", null);
        preferences.put("semanticColors", semanticColors);
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", preferences)))
                .doesNotThrowAnyException();
    }

    // ---------- v4 逐字段非法值拒绝 ----------

    @Test
    void should_reject_when_layout_mode_invalid() {
        for (Object bad : new Object[]{"float", "sidebarx", 42}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(
                    v4Blob("layoutMode", bad)))
                    .as("layoutMode 非法值 %s 应拒绝".formatted(bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("layoutMode");
        }
    }

    @Test
    void should_reject_when_tab_style_invalid() {
        for (Object bad : new Object[]{"ribbon", "cardx", true}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(
                    v4Blob("tabStyle", bad)))
                    .as("tabStyle 非法值 %s 应拒绝".formatted(bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("tabStyle");
        }
    }

    @Test
    void should_reject_when_sider_tone_invalid() {
        for (Object bad : new Object[]{"midnight", "autox", 7}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(
                    v4Blob("siderTone", bad)))
                    .as("siderTone 非法值 %s 应拒绝".formatted(bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("siderTone");
        }
    }

    @Test
    void should_reject_when_topbar_widgets_not_object() {
        for (Object bad : new Object[]{42, "all", true}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(
                    v4Blob("topbarWidgets", bad)))
                    .as("topbarWidgets 非对象 %s 应拒绝".formatted(bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("topbarWidgets");
        }
    }

    @Test
    void should_accept_legacy_release_widget_when_theme_preference_validated() {
        // REQ-20261126-125836 AC-7：release 键仍在 TOPBAR_WIDGET_KEYS 白名单内（legacy-tolerant），
        // true/false 两值均不抛校验异常，其余 widget 白名单行为不变。
        for (boolean legacyValue : new boolean[]{false, true}) {
            Map<String, Object> widgets = allWidgetsTrue();
            widgets.put("release", legacyValue);
            Map<String, Object> payload = v4Blob("topbarWidgets", widgets);
            assertThatCode(() -> ThemePreferenceValidator.validate(payload))
                    .as("legacy topbarWidgets.release=%s 应被接受".formatted(legacyValue))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void should_reject_when_topbar_widgets_key_unknown() {
        Map<String, Object> widgets = allWidgetsTrue();
        widgets.put("rogueWidget", true);
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(v4Blob("topbarWidgets", widgets)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("topbarWidgets");
    }

    @Test
    void should_reject_when_topbar_widgets_value_not_boolean() {
        Map<String, Object> widgets = allWidgetsTrue();
        widgets.put("search", "yes");
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(v4Blob("topbarWidgets", widgets)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("topbarWidgets");
    }

    @Test
    void should_reject_when_v4_carries_unknown_preference_key() {
        // 白名单扩展不越界：v4 字段齐备之外仍拒绝未知偏好键（确认记录②「未知字段拒绝口径不变」）
        Map<String, Object> preferences = fullV4Preferences("sidebar", "line", "auto");
        preferences.put("notAPreferenceKey", true);
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 4, "preferences", preferences)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知偏好键");
    }

    // ---------- 错误码段 ----------

    @Test
    void should_reject_with_existing_validation_error_code_segment_when_v4_invalid() {
        // 业务错误信封口径：BizException → 统一响应体 Result.code≠0（wire 码段 SYS-01-xxxx）
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(v4Blob("layoutMode", "float")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .matches("SYS-01-\\d{4}"));
    }

    // ---------- 支撑 ----------

    /** v4 全字段合法偏好（layoutMode/tabStyle/siderTone 传参 + topbarWidgets 全键布尔）。 */
    private static Map<String, Object> fullV4Preferences(String layoutMode, String tabStyle, String siderTone) {
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("mode", "dark");
        preferences.put("accent", "cyan");
        preferences.put("customAccent", "#5aa7a0");
        preferences.put("density", "comfortable");
        preferences.put("motionEnabled", true);
        preferences.put("radius", "medium");
        preferences.put("grayMode", false);
        preferences.put("weakMode", false);
        preferences.put("contentWidth", "fluid");
        preferences.put("layoutMode", layoutMode);
        preferences.put("tabStyle", tabStyle);
        preferences.put("siderTone", siderTone);
        preferences.put("topbarWidgets", allWidgetsTrue());
        return preferences;
    }

    /** topbarWidgets 全显缺省（契约②：缺省全显=现状逐字）。 */
    private static Map<String, Object> allWidgetsTrue() {
        Map<String, Object> widgets = new LinkedHashMap<>();
        for (String key : WIDGET_KEYS) {
            widgets.put(key, true);
        }
        return widgets;
    }

    /** 单 v4 偏好键坏值 blob（其余字段取合法最小集，沿用既有测试 badBlob 形态）。 */
    private static Map<String, Object> v4Blob(String badKey, Object badValue) {
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("mode", "dark");
        preferences.put("accent", "cyan");
        preferences.put("layoutMode", "sidebar");
        preferences.put("tabStyle", "line");
        preferences.put("siderTone", "auto");
        preferences.put("topbarWidgets", allWidgetsTrue());
        preferences.put(badKey, badValue);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 4);
        root.put("preferences", preferences);
        return root;
    }
}
