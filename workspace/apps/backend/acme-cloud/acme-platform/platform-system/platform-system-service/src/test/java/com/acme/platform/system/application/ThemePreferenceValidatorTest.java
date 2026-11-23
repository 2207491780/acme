package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ThemePreferenceValidator} 结构校验器单元测试（REQ-20261121-200415 确认记录③ fail-closed
 * 矩阵，字段集与 core preferences.ts 同形——t3-frontend 2026-11-21 字段表；THEME-4
 * REQ-20261122-233427 确认记录② v4 新增键 layoutMode/tabStyle/siderTone/topbarWidgets
 * 合法/非法矩阵同文件承载；纯 JUnit 无 Spring）。
 * 端到端两域语义（upsert/原数据不动/缺省回显）由 {@code REQ20261121200415PreferenceTwoDomainIntegrationTest}
 * 承载（Testcontainers PG）。
 */
class ThemePreferenceValidatorTest {

    // ---------- 合法形态 ----------

    @Test
    void should_pass_when_minimal_valid_blob() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3,
                "preferences", Map.of("mode", "dark", "accent", "cyan", "customAccent",
                        "#5aa7a0", "density", "comfortable", "motionEnabled", true))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_preference_keys_subset() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", Map.of("mode", "dark"))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_schema_version_any_integer() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 99, "preferences", Map.of("mode", "system"))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_extensions_present_as_object() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3,
                "preferences", Map.of("mode", "dark"),
                "extensions", Map.of("widget", Map.of("label", "hi", "n", 7)))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_nullable_preference_values_null() {
        assertThatCode(() -> ThemePreferenceValidator.validate(badBlob("customAccent", null)))
                .doesNotThrowAnyException();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("success", null);
        overrides.put("warning", "#ff5a5f");
        overrides.put("danger", "#5f5");
        assertThatCode(() -> ThemePreferenceValidator.validate(badBlob("semanticColors", overrides)))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_semantic_colors_has_extra_keys() {
        assertThatCode(() -> ThemePreferenceValidator.validate(badBlob(
                "semanticColors", Map.of("success", "#ff5a5f", "muted", "not-a-color"))))
                .doesNotThrowAnyException();
    }

    // ---------- 顶层结构 ----------

    @Test
    void should_reject_when_theme_preference_not_object() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate("not-an-object"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须是 JSON 对象");
    }

    @Test
    void should_reject_when_unknown_top_level_key() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", Map.of("mode", "dark"), "mysteryTopKey", true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知顶层键");
    }

    @Test
    void should_reject_when_schema_version_not_integer() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 1.5, "preferences", Map.of("mode", "dark"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void should_reject_when_preferences_missing_or_not_object() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of("schemaVersion", 3)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("preferences");
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", "dark")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("preferences");
    }

    // ---------- preferences 逐键 ----------

    @Test
    void should_reject_when_unknown_preference_key() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", Map.of("mode", "dark", "notAPreferenceKey", true))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知偏好键");
    }

    @Test
    void should_reject_when_contract_stale_keys_used() {
        for (String staleKey : new String[]{"motion", "radiusScale", "semanticColorOverrides", "followSystem"}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(staleKey, true)))
                    .as("stale 键 %s 应按未知偏好键拒绝".formatted(staleKey))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("未知偏好键");
        }
    }

    @Test
    void should_reject_when_enum_value_invalid() {
        Map<String, String> badValues = Map.of(
                "mode", "sepia",
                "accent", "hotpink",
                "density", "spacious",
                "radius", "gigantic",
                "contentWidth", "wide");
        for (Map.Entry<String, String> bad : badValues.entrySet()) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(bad.getKey(), bad.getValue())))
                    .as("偏好键 %s 坏枚举应拒绝".formatted(bad.getKey()))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    void should_reject_when_boolean_field_not_boolean() {
        for (String key : new String[]{"motionEnabled", "grayMode", "weakMode"}) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(key, "true")))
                    .as("偏好键 %s 非布尔应拒绝".formatted(key))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    void should_reject_when_hex_field_malformed() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob("customAccent", "#12345")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("customAccent");
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob("customAccent", "5aa7a0")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("customAccent");
    }

    @Test
    void should_reject_when_semantic_colors_bad_value_or_not_object() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(
                "semanticColors", Map.of("danger", "red"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("danger");
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(
                "semanticColors", "dark")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("semanticColors");
    }

    // ---------- THEME-4 v4 新增键（REQ-20261122-233427 确认记录②） ----------

    @Test
    void should_pass_when_v4_new_fields_present() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 4,
                "preferences", Map.of(
                        "mode", "dark",
                        "layoutMode", "mixed",
                        "tabStyle", "card",
                        "siderTone", "dark",
                        "topbarWidgets", Map.of(
                                "search", true, "bell", false, "lang", true, "timezone", false,
                                "appearance", true, "themeToggle", true, "lock", false,
                                "fullscreen", true, "settings", true, "release", false)))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_v4_blob_without_new_fields() {
        assertThatCode(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 4, "preferences", Map.of("mode", "dark"))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_topbar_widgets_empty_or_partial_subset() {
        assertThatCode(() -> ThemePreferenceValidator.validate(
                badBlob("topbarWidgets", Map.of("bell", false, "fullscreen", true))))
                .doesNotThrowAnyException();
        assertThatCode(() -> ThemePreferenceValidator.validate(badBlob("topbarWidgets", Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_when_new_enum_fields_bad_value() {
        Map<String, String> badValues = Map.of(
                "layoutMode", "floating",
                "tabStyle", "ribbon",
                "siderTone", "light");
        for (Map.Entry<String, String> bad : badValues.entrySet()) {
            assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob(bad.getKey(), bad.getValue())))
                    .as("偏好键 %s 坏枚举应拒绝".formatted(bad.getKey()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining(bad.getKey());
        }
    }

    @Test
    void should_reject_when_topbar_widgets_not_object() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob("topbarWidgets", "all")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("topbarWidgets");
    }

    @Test
    void should_reject_when_topbar_widgets_unknown_key() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob("topbarWidgets",
                Map.of("search", true, "themeSwitch", true))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知偏好键")
                .hasMessageContaining("topbarWidgets.themeSwitch");
    }

    @Test
    void should_reject_when_topbar_widgets_value_not_boolean() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(badBlob("topbarWidgets",
                Map.of("bell", "yes"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("topbarWidgets.bell");
    }

    // ---------- extensions 结构 ----------

    @Test
    void should_reject_when_extensions_not_object() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", Map.of("mode", "dark"), "extensions", "big")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("extensions");
    }

    // ---------- 错误码段 ----------

    @Test
    void should_reject_with_existing_validation_error_code_segment() {
        assertThatThrownBy(() -> ThemePreferenceValidator.validate(Map.of(
                "schemaVersion", 3, "preferences", "dark")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .matches("SYS-01-\\d{4}"));
    }

    // ---------- 支撑 ----------

    /** 单偏好键坏值 blob（其余合法最小集）。 */
    private static Map<String, Object> badBlob(String badKey, Object badValue) {
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("mode", "dark");
        preferences.put(badKey, badValue);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 3);
        root.put("preferences", preferences);
        return root;
    }
}
