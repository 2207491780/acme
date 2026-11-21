package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * themePreference 结构校验器（REQ-20261121-200415 确认记录③，fail-closed；字段集与前端 core
 * preferences.ts 严格同形——t3-frontend 2026-11-21 字段表；契约③文本中 motion/radiusScale/
 * semanticColorOverrides/followSystem 为 stale 名，core 真实键为 motionEnabled/radius/
 * semanticColors，followSystem 不存在）：
 * <ul>
 *   <li>顶层键白名单：schemaVersion(int，不钉死=3，迁移归前端 migrateSettings)/
 *       preferences(object)/extensions(object 可省)；</li>
 *   <li>preferences 逐键白名单 + 类型 + 枚举 + hex（仅显式存在时校验——缺省键合法，
 *       与 core validateThemePreferences 宽容缺省口径一致）；</li>
 *   <li>semanticColors 三键 success/warning/danger 值 hex|null，多余键容忍透传
 *       （core isSemanticColorOverrides every 只查三键口径）；</li>
 *   <li>extensions 仅整体结构校验（object；单键 ≤16KB、总 ≤64KB 序列化计量在用例侧），
 *       内容透传不深检（确认记录⑤注册面语义）；</li>
 *   <li>非法 → {@code BizException(WebError.VALIDATION_FAILED)}（400 SYS-01-0001 既有校验
 *       错误码段，REQ-082531 终稿口径，不新增错误码段）；校验为纯函数，调用方保证先校验后
 *       写入（原数据不动）。</li>
 * </ul>
 */
final class ThemePreferenceValidator {

    /** 顶层键白名单（extensions 可省；缺任一必省键即非法）。 */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("schemaVersion", "preferences", "extensions");

    /** mode 三态（跟随系统三态，确认记录⑦）。 */
    private static final Set<String> MODES = Set.of("light", "dark", "system");

    /** accent 预设枚举（core theme.ts ACCENTS/ACCENT_ORDER 12 预设键名，存键名非色值）。 */
    static final Set<String> ACCENTS = Set.of(
            "cyan", "blue", "violet", "magenta", "amber", "acid",
            "emerald", "sky", "coral", "ruby", "bronze", "slate");

    /** density 档位（core DENSITY）。 */
    private static final Set<String> DENSITIES = Set.of("comfortable", "compact");

    /** radius 档位（THEME-2 v3 四档，blob 存枚举，数值缩放归解析层）。 */
    private static final Set<String> RADII = Set.of("small", "medium", "large", "full");

    /** contentWidth 档位（THEME-2 v3）。 */
    private static final Set<String> CONTENT_WIDTHS = Set.of("fluid", "compact");

    /** semanticColors 语义色覆盖键白名单（仅三键；muted/info/violet 不可覆盖——THEME-2 确认⑤）。 */
    private static final Set<String> SEMANTIC_KEYS = Set.of("success", "warning", "danger");

    /** hex（#RGB | #RRGGBB，与 core 同一 pattern：3/6 位、大小写均收）。 */
    private static final Pattern HEX_COLOR = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private ThemePreferenceValidator() {
    }

    /** 校验入口（fail-closed：任何违规即抛 400 校验错误码）。 */
    static void validate(Object themePreference) {
        if (!(themePreference instanceof Map<?, ?> root)) {
            throw invalid("themePreference 必须是 JSON 对象");
        }
        for (Object key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(String.valueOf(key))) {
                throw invalid("未知顶层键: " + key);
            }
        }
        Object schemaVersion = root.get("schemaVersion");
        if (!(schemaVersion instanceof Integer || schemaVersion instanceof Long)) {
            throw invalid("schemaVersion 必须是整数");
        }
        if (!(root.get("preferences") instanceof Map<?, ?> preferences)) {
            throw invalid("preferences 必须是 JSON 对象");
        }
        for (Map.Entry<?, ?> entry : preferences.entrySet()) {
            validatePreference(String.valueOf(entry.getKey()), entry.getValue());
        }
        if (root.get("extensions") != null) {
            validateExtensions(root.get("extensions"));
        }
    }

    /** preferences 逐键白名单 + 类型 + 枚举 + hex（仅显式存在时校验——缺省键合法）。 */
    private static void validatePreference(String key, Object value) {
        BiConsumer<String, Object> rule = PREFERENCE_RULES.get(key);
        if (rule == null) {
            throw invalid("未知偏好键: " + key);
        }
        rule.accept(key, value);
    }

    /**
     * 偏好键规则表（key → 校验闭包；与 core ThemePreferences 字段集一一对应：
     * mode/accent/customAccent/density/motionEnabled/radius/grayMode/weakMode/contentWidth/semanticColors）。
     */
    private static final Map<String, BiConsumer<String, Object>> PREFERENCE_RULES = Map.of(
            "mode", (k, v) -> requireEnum(k, v, MODES),
            "accent", (k, v) -> requireEnum(k, v, ACCENTS),
            "customAccent", (k, v) -> requireHexOrNull(k, v),
            "density", (k, v) -> requireEnum(k, v, DENSITIES),
            "motionEnabled", (k, v) -> requireBoolean(k, v),
            "radius", (k, v) -> requireEnum(k, v, RADII),
            "grayMode", (k, v) -> requireBoolean(k, v),
            "weakMode", (k, v) -> requireBoolean(k, v),
            "contentWidth", (k, v) -> requireEnum(k, v, CONTENT_WIDTHS),
            "semanticColors", (k, v) -> requireSemanticColors(k, v)
    );

    private static void requireEnum(String key, Object value, Set<String> allowed) {
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw invalid("偏好键 %s 取值非法（允许: %s）".formatted(key, String.join("/", allowed)));
        }
    }

    private static void requireBoolean(String key, Object value) {
        if (!(value instanceof Boolean)) {
            throw invalid("偏好键 %s 必须是布尔值".formatted(key));
        }
    }

    private static void requireHexOrNull(String key, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof String text) || !HEX_COLOR.matcher(text).matches()) {
            throw invalid("偏好键 %s 必须是 hex 颜色或 null".formatted(key));
        }
    }

    /**
     * semanticColors：object、success/warning/danger 值 hex 或 null（null=用 TONES 基线）；
     * 三键之外的键容忍透传不深检（core isSemanticColorOverrides every 只查三键口径）。
     */
    private static void requireSemanticColors(String key, Object value) {
        if (!(value instanceof Map<?, ?> overrides)) {
            throw invalid("偏好键 %s 必须是 JSON 对象".formatted(key));
        }
        for (Map.Entry<?, ?> entry : overrides.entrySet()) {
            String tone = String.valueOf(entry.getKey());
            if (!SEMANTIC_KEYS.contains(tone)) {
                continue;
            }
            requireHexOrNull(key + "." + tone, entry.getValue());
        }
    }

    /** extensions 结构校验：object、值内容透传不深检（上界校验经 Json 序列化计量，见用例侧）。 */
    private static void validateExtensions(Object extensions) {
        if (!(extensions instanceof Map<?, ?>)) {
            throw invalid("extensions 必须是 JSON 对象");
        }
    }

    private static BizException invalid(String message) {
        return new BizException(WebError.VALIDATION_FAILED, "主题偏好非法: " + message);
    }
}
