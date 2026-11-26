package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * themePreference 结构校验器（REQ-20261121-200415 确认记录③ + THEME-4 REQ-20261122-233427
 * 确认记录② schema v4，fail-closed；字段集与前端 core preferences.ts 严格同形——t3-frontend
 * 2026-11-21 字段表；契约③文本中 motion/radiusScale/semanticColorOverrides/followSystem 为
 * stale 名，core 真实键为 motionEnabled/radius/semanticColors，followSystem 不存在）：
 * <ul>
 *   <li>顶层键白名单：schemaVersion(int，不钉死版本——v3/v4 皆合法，迁移归前端
 *       migrateSettings)/preferences(object)/extensions(object 可省)；</li>
 *   <li>preferences 逐键白名单 + 类型 + 枚举 + hex（仅显式存在时校验——缺省键合法，
 *       与 core validateThemePreferences 宽容缺省口径一致）；</li>
 *   <li>THEME-4 v4 新增偏好键：layoutMode/tabStyle/siderTone（枚举）与 topbarWidgets
 *       （Record&lt;WidgetKey,boolean&gt;：键白名单、未知 widget 键按既有未知偏好键同口径
 *       拒绝）——沿用「显式存在才校验」，未传新字段的 v4 与 v3 载荷均合法；</li>
 *   <li>legacy 容忍：{@code topbarWidgets.release} 是「多端发布」演示功能遗留的 widget 键，
 *       REQ-20261126-125836 已移除前端对应入口（UI 不再渲染新建发布单按钮），但本校验器继续
 *       接受该键，以兼容历史已持久化的偏好 blob——避免 fail-closed 校验对旧数据返回 400。
 *       该键只保留不删除，且不得改变任何既有校验行为；其它键仍按白名单 fail-closed。</li>
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

    /** layoutMode 布局三态（THEME-4 v4 确认记录②，缺省 sidebar=现行为）。 */
    private static final Set<String> LAYOUT_MODES = Set.of("sidebar", "top", "mixed");

    /** tabStyle 页签四风格（THEME-4 v4 确认记录②④，line=现行为缺省）。 */
    private static final Set<String> TAB_STYLES = Set.of("line", "card", "chrome", "plain");

    /** siderTone 半深色档（THEME-4 v4 确认记录②⑤，auto=现行为；dark 仅 light 模式生效，联动归前端）。 */
    private static final Set<String> SIDER_TONES = Set.of("auto", "dark");

    /**
     * topbarWidgets widget 键白名单（THEME-4 v4 确认记录②，10 键；未知键拒绝）。
     * {@code release} 为 REQ-20261126-125836 的 legacy-tolerant 保留键：UI 已不再渲染新建发布单
     * 按钮，后端仅为兼容历史偏好 blob 继续接受该键；不得删除，不得改变其校验行为。
     */
    static final Set<String> TOPBAR_WIDGET_KEYS = Set.of(
            "search", "bell", "lang", "timezone", "appearance",
            "themeToggle", "lock", "fullscreen", "settings", "release");

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
     * v3 十键 mode/accent/customAccent/density/motionEnabled/radius/grayMode/weakMode/
     * contentWidth/semanticColors + THEME-4 v4 四键 layoutMode/tabStyle/siderTone/topbarWidgets；
     * >10 键用 ofEntries）。
     */
    private static final Map<String, BiConsumer<String, Object>> PREFERENCE_RULES = Map.ofEntries(
            Map.entry("mode", (k, v) -> requireEnum(k, v, MODES)),
            Map.entry("accent", (k, v) -> requireEnum(k, v, ACCENTS)),
            Map.entry("customAccent", (k, v) -> requireHexOrNull(k, v)),
            Map.entry("density", (k, v) -> requireEnum(k, v, DENSITIES)),
            Map.entry("motionEnabled", (k, v) -> requireBoolean(k, v)),
            Map.entry("radius", (k, v) -> requireEnum(k, v, RADII)),
            Map.entry("grayMode", (k, v) -> requireBoolean(k, v)),
            Map.entry("weakMode", (k, v) -> requireBoolean(k, v)),
            Map.entry("contentWidth", (k, v) -> requireEnum(k, v, CONTENT_WIDTHS)),
            Map.entry("semanticColors", (k, v) -> requireSemanticColors(k, v)),
            Map.entry("layoutMode", (k, v) -> requireEnum(k, v, LAYOUT_MODES)),
            Map.entry("tabStyle", (k, v) -> requireEnum(k, v, TAB_STYLES)),
            Map.entry("siderTone", (k, v) -> requireEnum(k, v, SIDER_TONES)),
            Map.entry("topbarWidgets", (k, v) -> requireTopbarWidgets(k, v))
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

    /**
     * topbarWidgets（THEME-4 v4 确认记录②）：object、键⊆{@link #TOPBAR_WIDGET_KEYS}
     * （未知 widget 键按既有未知偏好键同口径拒绝，与顶层/偏好未知键 fail-closed 一致）、
     * 值必须 boolean；空对象/部分子集/缺省键均合法（缺省全显与重置语义归前端）。
     */
    private static void requireTopbarWidgets(String key, Object value) {
        if (!(value instanceof Map<?, ?> widgets)) {
            throw invalid("偏好键 %s 必须是 JSON 对象".formatted(key));
        }
        for (Map.Entry<?, ?> entry : widgets.entrySet()) {
            String widgetKey = String.valueOf(entry.getKey());
            if (!TOPBAR_WIDGET_KEYS.contains(widgetKey)) {
                throw invalid("未知偏好键: " + key + "." + widgetKey);
            }
            if (!(entry.getValue() instanceof Boolean)) {
                throw invalid("偏好键 %s.%s 必须是布尔值".formatted(key, widgetKey));
            }
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
