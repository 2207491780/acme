import { ACCENTS, ACCENT_ORDER, ON_ACCENT, type SemanticColorOverrides } from './theme';
import type {
  Accent,
  ContentWidth,
  Density,
  LayoutMode,
  RadiusScale,
  Settings,
  SiderTone,
  TabStyle,
  Theme,
  TopbarWidgetKey,
  TopbarWidgets,
} from './types';

/**
 * 主题偏好引擎（REQ-20261121-010725 THEME-1；REQ-20261121-043502 THEME-2 v3；
 * REQ-20261122-233427 THEME-4 v4）：
 * 偏好 schema/默认值/运行时校验/版本迁移/系统主题解析。
 * 框架无关（不引 React）；system 模式的 matchMedia 监听归端侧（web/src/hooks）。
 * 外部输入一律运行时校验：非法字段逐项降级默认、不抛错、不覆盖既有合法字段（编码规范红线）。
 */

/** 主题模式三态：system = 跟随 prefers-color-scheme（端侧解析 + 监听）。 */
export type ThemeMode = 'light' | 'dark' | 'system';

/** 主题偏好（settings.preferences 的内嵌形状，唯一事实源；types.ts 经 type-only 引用）。 */
export interface ThemePreferences {
  mode: ThemeMode;
  accent: Accent;
  /** 自定义取色（AntD ColorPicker 产物，#hex 小写 6 位）；null = 未自定义（用预设 accent）。 */
  customAccent: string | null;
  density: Density;
  motionEnabled: boolean;
  /** 圆角档位（THEME-2 v3）：medium=现状基线；small/large 缩放；full=胶囊（映射见 theme.resolveRadiusTokens）。 */
  radius: RadiusScale;
  /** 灰度模式（THEME-2 v3）：根容器 grayscale(1) 滤镜（web 层 data-surface 接线）。 */
  grayMode: boolean;
  /** 色弱模式（THEME-2 v3）：根容器 SVG 矩阵滤镜（web 层 data-surface 接线）。 */
  weakMode: boolean;
  /** 内容宽度（THEME-2 v3）：fluid=不限宽；compact=内容区定宽居中。 */
  contentWidth: ContentWidth;
  /** 语义色覆盖（THEME-2 v3）：success/warning/danger 各自 null=TONES 基线（解析见 theme.resolveSemanticColors）。 */
  semanticColors: SemanticColorOverrides;
  /** 布局模式（THEME-4 v4 确认②③）：sidebar=现状；top=顶栏主导航；mixed=顶栏一级+图标轨。 */
  layoutMode: LayoutMode;
  /** 页签风格（THEME-4 v4 确认④）：line=现状；card/chrome/plain 见 TabBar。 */
  tabStyle: TabStyle;
  /** 侧栏色调（THEME-4 v4 确认⑤）：auto=现状；dark=浅色模式半深色（暗色模式无效）。 */
  siderTone: SiderTone;
  /** 顶栏 widget 显隐（THEME-4 v4 确认②⑥）：键位全集布尔开关，缺省全显；与租户级水印/页脚正交。 */
  topbarWidgets: TopbarWidgets;
  /** 扩展偏好域（REQ-20261121-200415 THEME-3 契约⑤）：仅当存在注册域时出现；
   *  键=注册键（经 spec.parse 降级）或未注册透传键（原样存储，不做结构解释）。 */
  extensions?: Record<string, unknown>;
}

/** 偏好子结构版本号：写入 settings.schemaVersion，无版本标记的旧 settings 经 migrateSettings 迁移。 */
export const PREFERENCES_VERSION = 4;

/** 出厂默认偏好（恢复默认/校验降级的基准；mode=light 为契约钉死值；新版本字段默认=现状外观）。 */
export const DEFAULT_PREFERENCES: ThemePreferences = {
  mode: 'light',
  accent: 'cyan',
  customAccent: null,
  density: 'comfortable',
  motionEnabled: true,
  radius: 'medium',
  grayMode: false,
  weakMode: false,
  contentWidth: 'fluid',
  semanticColors: { success: null, warning: null, danger: null },
  // —— v4 字段（REQ-20261122-233427 确认②）：默认=现状外观（sidebar/line/auto/全显）——
  layoutMode: 'sidebar',
  tabStyle: 'line',
  siderTone: 'auto',
  topbarWidgets: {
    search: true,
    bell: true,
    lang: true,
    timezone: true,
    appearance: true,
    themeToggle: true,
    lock: true,
    fullscreen: true,
    settings: true,
    release: true,
  },
};

const THEME_MODES: ThemeMode[] = ['light', 'dark', 'system'];
const DENSITIES: Density[] = ['comfortable', 'compact'];
const RADII_SCALES: RadiusScale[] = ['small', 'medium', 'large', 'full'];
const CONTENT_WIDTHS: ContentWidth[] = ['fluid', 'compact'];
const SEMANTIC_KEYS: (keyof SemanticColorOverrides)[] = ['success', 'warning', 'danger'];
// —— v4 枚举域（REQ-20261122-233427 确认②）——
const LAYOUT_MODES: LayoutMode[] = ['sidebar', 'top', 'mixed'];
const TAB_STYLES: TabStyle[] = ['line', 'card', 'chrome', 'plain'];
const SIDER_TONES: SiderTone[] = ['auto', 'dark'];
const TOPBAR_WIDGET_KEYS: TopbarWidgetKey[] = [
  'search', 'bell', 'lang', 'timezone', 'appearance',
  'themeToggle', 'lock', 'fullscreen', 'settings', 'release',
];

// —— 扩展偏好注册机制（REQ-20261121-200415 THEME-3 契约⑤）——

/** 扩展偏好域规格：defaults=出厂值；parse 将外部输入归一化为域值，非法降级 defaults 且不抛错。 */
export interface PreferenceExtensionSpec<T = unknown> {
  defaults: T;
  parse: (value: unknown) => T;
}

/** 扩展偏好注册表（内部状态）：registerPreferenceExtension 为唯一写入口。 */
const PREFERENCE_EXTENSION_REGISTRY = new Map<string, PreferenceExtensionSpec>();

/**
 * 注册扩展偏好域（THEME-3 契约⑤，最小机制）：
 * - 注册后 validateThemePreferences 对该域调用 spec.parse 降级（非法/抛错 → defaults）；
 * - 未注册键在 extensions 中原样透传存储，不做结构解释（后端亦仅整体结构校验，契约③）；
 * - 注册表非空时 preferences 才出现 extensions 键（AC-5：仅注册后出现）；
 * - 同键重复注册以后注册者为准（幂等，不抛错）。
 */
export function registerPreferenceExtension<T>(key: string, spec: PreferenceExtensionSpec<T>): void {
  PREFERENCE_EXTENSION_REGISTRY.set(key, spec);
}

/** 已注册扩展域键集（只读快照；测试/诊断面）。 */
export function registeredPreferenceExtensionKeys(): string[] {
  return [...PREFERENCE_EXTENSION_REGISTRY.keys()];
}

/** 扩展域归一化（契约⑤）：注册键经 spec.parse 降级（抛错兜底 defaults）；未注册键透传。 */
function normalizeExtensions(raw: unknown): Record<string, unknown> {
  const source = isRecord(raw) ? raw : {};
  const extensions: Record<string, unknown> = {};
  for (const [key, spec] of PREFERENCE_EXTENSION_REGISTRY) {
    let value: unknown;
    try {
      value = key in source ? spec.parse(source[key]) : spec.defaults;
    } catch {
      value = spec.defaults;
    }
    extensions[key] = value;
  }
  for (const [key, value] of Object.entries(source)) {
    if (!PREFERENCE_EXTENSION_REGISTRY.has(key)) extensions[key] = value;
  }
  return extensions;
}

// 与 chart-option 的 HEX_RE 同口径：3/6 位 hex（theme.parseHex/mixHex 均兼容展开）。
const HEX_RE = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

export function isThemeModeValue(value: unknown): value is ThemeMode {
  return THEME_MODES.some((m) => m === value);
}

export function isAccentKey(value: unknown): value is Accent {
  return ACCENT_ORDER.some((a) => a === value);
}

export function isDensityValue(value: unknown): value is Density {
  return DENSITIES.some((d) => d === value);
}

export function isRadiusScale(value: unknown): value is RadiusScale {
  return RADII_SCALES.some((r) => r === value);
}

export function isContentWidth(value: unknown): value is ContentWidth {
  return CONTENT_WIDTHS.some((w) => w === value);
}

// —— v4 校验器（REQ-20261122-233427 确认②；供 parse-state fail-closed 与 msm 校验同口径复用）——

export function isLayoutMode(value: unknown): value is LayoutMode {
  return LAYOUT_MODES.some((m) => m === value);
}

export function isTabStyle(value: unknown): value is TabStyle {
  return TAB_STYLES.some((s) => s === value);
}

export function isSiderTone(value: unknown): value is SiderTone {
  return SIDER_TONES.some((s) => s === value);
}

export function isTopbarWidgetKey(value: unknown): value is TopbarWidgetKey {
  return TOPBAR_WIDGET_KEYS.some((k) => k === value);
}

/** 顶栏 widget 开关结构校验（THEME-4 确认②）：键位须为 WidgetKey 子集、值须为布尔。 */
export function isTopbarWidgets(value: unknown): value is TopbarWidgets {
  if (!isRecord(value)) return false;
  return Object.entries(value).every(
    ([key, item]) => isTopbarWidgetKey(key) && typeof item === 'boolean',
  );
}

/** 语义色覆盖形状校验：success/warning/danger 三键均须为 null 或合法 hex（供 parse-state fail-closed 复用）。 */
export function isSemanticColorOverrides(value: unknown): value is SemanticColorOverrides {
  if (!isRecord(value)) return false;
  return SEMANTIC_KEYS.every((key) => value[key] === null || isHexColor(value[key]));
}

export function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && HEX_RE.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isLegacyTheme(value: unknown): value is Theme {
  return value === 'dark' || value === 'light';
}

/** 自定义取色归一化：小写 + 3 位展开为 6 位；非法输入返回 null（降级为未自定义）。 */
function normalizeHexColor(value: unknown): string | null {
  if (!isHexColor(value)) return null;
  const lower = value.toLowerCase();
  const full =
    lower.length === 4 ? `#${lower[1]}${lower[1]}${lower[2]}${lower[2]}${lower[3]}${lower[3]}` : lower;
  return full;
}

/**
 * 语义色覆盖归一化（THEME-2 v3）：整段缺省/非法 → 全 null（回 TONES 基线）；
 * 逐键 null/undefined → null；合法 hex → 小写 6 位归一化；非法 hex → null（降级为基线）。
 */
function normalizeSemanticColors(value: unknown): SemanticColorOverrides {
  const raw = isRecord(value) ? value : {};
  const field = (v: unknown): string | null =>
    v === undefined || v === null ? null : normalizeHexColor(v);
  return {
    success: field(raw.success),
    warning: field(raw.warning),
    danger: field(raw.danger),
  };
}

/**
 * 顶栏 widget 开关归一化（THEME-4 v4 确认②）：整段缺省/非法 → 缺省全显；逐键仅接受
 * WidgetKey 子集内的布尔值（未知键剔除、非法值/缺键降级 true），每次产出独立对象避免共享引用。
 */
function normalizeTopbarWidgets(value: unknown): TopbarWidgets {
  const raw = isRecord(value) ? value : {};
  const widgets = {} as TopbarWidgets;
  for (const key of TOPBAR_WIDGET_KEYS) {
    widgets[key] = typeof raw[key] === 'boolean' ? (raw[key] as boolean) : true;
  }
  return widgets;
}

/** 逐字段校验并归一化偏好：非法字段降级默认、合法字段原样保留，永不抛错。 */
export function validateThemePreferences(input: unknown): ThemePreferences {
  const raw = isRecord(input) ? input : {};
  const result: ThemePreferences = {
    mode: isThemeModeValue(raw.mode) ? raw.mode : DEFAULT_PREFERENCES.mode,
    accent: isAccentKey(raw.accent) ? raw.accent : DEFAULT_PREFERENCES.accent,
    customAccent:
      raw.customAccent === undefined || raw.customAccent === null
        ? null
        : normalizeHexColor(raw.customAccent),
    density: isDensityValue(raw.density) ? raw.density : DEFAULT_PREFERENCES.density,
    motionEnabled:
      typeof raw.motionEnabled === 'boolean' ? raw.motionEnabled : DEFAULT_PREFERENCES.motionEnabled,
    // —— v3 字段（REQ-20261121-043502 确认②）：未知档/非布尔/非法 hex 逐项降级默认，不抛错 ——
    radius: isRadiusScale(raw.radius) ? raw.radius : DEFAULT_PREFERENCES.radius,
    grayMode: typeof raw.grayMode === 'boolean' ? raw.grayMode : DEFAULT_PREFERENCES.grayMode,
    weakMode: typeof raw.weakMode === 'boolean' ? raw.weakMode : DEFAULT_PREFERENCES.weakMode,
    contentWidth: isContentWidth(raw.contentWidth) ? raw.contentWidth : DEFAULT_PREFERENCES.contentWidth,
    semanticColors: normalizeSemanticColors(raw.semanticColors),
    // —— v4 字段（REQ-20261122-233427 确认②）：未知枚举/非法开关逐项降级默认，不抛错 ——
    layoutMode: isLayoutMode(raw.layoutMode) ? raw.layoutMode : DEFAULT_PREFERENCES.layoutMode,
    tabStyle: isTabStyle(raw.tabStyle) ? raw.tabStyle : DEFAULT_PREFERENCES.tabStyle,
    siderTone: isSiderTone(raw.siderTone) ? raw.siderTone : DEFAULT_PREFERENCES.siderTone,
    topbarWidgets: normalizeTopbarWidgets(raw.topbarWidgets),
  };
  // 扩展域（THEME-3 契约⑤）：仅当存在注册域时出现 extensions 键（AC-5）
  if (PREFERENCE_EXTENSION_REGISTRY.size > 0) {
    result.extensions = normalizeExtensions(raw.extensions);
  }
  return result;
}

/**
 * 旧 settings → 当前版本 preferences 的无损迁移（REQ-20261121-010725 确认③；THEME-2 v2→v3；
 * THEME-4 v3→v4）：
 * - 无版本标记（v1 时代扁平形状）直迁当前版本：theme('dark'|'light')→mode（旧「深色 Switch」
 *   语义；dark:boolean 旧语义同样接受，theme 优先）、accent/density/motion→同名偏好字段；
 * - v2/v3 blob（schemaVersion=2/3）→ v4：旧字段无损保留，新版本字段补默认（v3 组 radius=medium/
 *   gray=false/weak=false/width=fluid/semantic 全 null；v4 组 layoutMode=sidebar/tabStyle=line/
 *   siderTone=auto/topbarWidgets 全显），不抛错；
 * - 迁移结果写入版本标记 schemaVersion = PREFERENCES_VERSION；
 * - 已带当前版本标记的新形状仅做逐字段校验归一化（migrated=false）；
 * - null/undefined/非法输入返回默认 settings，不抛错（migrated=false，无数据可迁移）。
 * THEME-3（契约⑧）：迁移窗口保留（旧投影 blob 无损迁移），产物不再含投影字段。
 */
export function migrateSettings(raw: unknown): { settings: Settings; migrated: boolean } {
  if (!isRecord(raw)) {
    return { settings: buildSettings(DEFAULT_PREFERENCES), migrated: false };
  }
  const prefs: ThemePreferences = {
    ...DEFAULT_PREFERENCES,
    // 子结构独立拷贝：避免 settings.preferences 的嵌套对象与出厂默认共享引用被就地改写
    semanticColors: { ...DEFAULT_PREFERENCES.semanticColors },
    topbarWidgets: { ...DEFAULT_PREFERENCES.topbarWidgets },
  };
  if (isRecord(raw.preferences)) {
    Object.assign(prefs, validateThemePreferences(raw.preferences));
  } else {
    // legacy 扁平形状（schemaVersion 出现前的 blob）：theme 优先，dark:boolean 兜底
    if (isLegacyTheme(raw.theme)) prefs.mode = raw.theme;
    else if (typeof raw.dark === 'boolean') prefs.mode = raw.dark ? 'dark' : 'light';
    if (isAccentKey(raw.accent)) prefs.accent = raw.accent;
    if (isDensityValue(raw.density)) prefs.density = raw.density;
    if (typeof raw.motion === 'boolean') prefs.motionEnabled = raw.motion;
    if (raw.customAccent !== undefined && raw.customAccent !== null && isHexColor(raw.customAccent)) {
      prefs.customAccent = normalizeHexColor(raw.customAccent);
    }
  }
  const hasValidVersion = typeof raw.schemaVersion === 'number' && Number.isFinite(raw.schemaVersion);
  const migrated = !hasValidVersion || raw.schemaVersion !== PREFERENCES_VERSION;
  return { settings: buildSettings(prefs), migrated };
}

/** mode=system 时按 prefers-color-scheme 解析生效主题；显式 light/dark 原样返回（手动选择脱离 system）。 */
export function resolveThemeMode(mode: ThemeMode, systemPrefersDark: boolean): Theme {
  if (mode === 'system') return systemPrefersDark ? 'dark' : 'light';
  return mode;
}

/** 生效强调色：自定义取色非 null 时优先（色值即 colorPrimary），否则取预设 ACCENTS[accent]。 */
export function resolveAccentColor(p: ThemePreferences): string {
  return p.customAccent ?? ACCENTS[p.accent];
}

/** 实心强调色之上的前景：预设/自定义统一取 ON_ACCENT 纯黑规则，保证暗色算法下 ≥4.5:1 对比度。 */
export function resolveOnAccent(p: ThemePreferences): string {
  return ON_ACCENT[p.accent];
}

/**
 * 由偏好组装完整 settings（唯一构造入口，seed/reducer/parse-state/migrateSettings 统一走此函数）：
 * 写入版本标记 schemaVersion。THEME-3（REQ-20261121-200415 契约⑧）已物理删除 4 个 @deprecated
 * 扁平投影字段及其派生逻辑——preferences 是唯一事实源，settings = { schemaVersion, preferences }。
 */
export function buildSettings(preferences: ThemePreferences): Settings {
  return { schemaVersion: PREFERENCES_VERSION, preferences };
}
