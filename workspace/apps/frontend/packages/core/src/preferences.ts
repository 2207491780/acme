import { ACCENTS, ACCENT_ORDER, ON_ACCENT } from './theme';
import type { Accent, Density, Settings, Theme } from './types';

/**
 * 主题偏好引擎（REQ-20261121-010725）：偏好 schema/默认值/运行时校验/版本迁移/系统主题解析。
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
}

/** 偏好子结构版本号：写入 settings.schemaVersion，无版本标记的旧 settings 经 migrateSettings 迁移。 */
export const PREFERENCES_VERSION = 2;

/** 出厂默认偏好（恢复默认/校验降级的基准；mode=light 为契约钉死值）。 */
export const DEFAULT_PREFERENCES: ThemePreferences = {
  mode: 'light',
  accent: 'cyan',
  customAccent: null,
  density: 'comfortable',
  motionEnabled: true,
};

const THEME_MODES: ThemeMode[] = ['light', 'dark', 'system'];
const DENSITIES: Density[] = ['comfortable', 'compact'];

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

/** 逐字段校验并归一化偏好：非法字段降级默认、合法字段原样保留，永不抛错。 */
export function validateThemePreferences(input: unknown): ThemePreferences {
  const raw = isRecord(input) ? input : {};
  return {
    mode: isThemeModeValue(raw.mode) ? raw.mode : DEFAULT_PREFERENCES.mode,
    accent: isAccentKey(raw.accent) ? raw.accent : DEFAULT_PREFERENCES.accent,
    customAccent:
      raw.customAccent === undefined || raw.customAccent === null
        ? null
        : normalizeHexColor(raw.customAccent),
    density: isDensityValue(raw.density) ? raw.density : DEFAULT_PREFERENCES.density,
    motionEnabled:
      typeof raw.motionEnabled === 'boolean' ? raw.motionEnabled : DEFAULT_PREFERENCES.motionEnabled,
  };
}

/**
 * 无版本标记的旧 settings → 内嵌 preferences 新形状的无损迁移（REQ-20261121-010725 确认③）：
 * - 旧扁平字段映射：theme('dark'|'light')→mode（旧「深色 Switch」语义；dark:boolean 旧语义同样
 *   接受，theme 优先）、accent/density/motion→同名偏好字段，全保留映射；
 * - 迁移结果写入版本标记 schemaVersion = PREFERENCES_VERSION；
 * - 已带版本标记的新形状仅做逐字段校验归一化（migrated=false）；
 * - null/undefined/非法输入返回默认 settings，不抛错（migrated=false，无数据可迁移）。
 */
export function migrateSettings(raw: unknown): { settings: Settings; migrated: boolean } {
  if (!isRecord(raw)) {
    return { settings: settingsFromPreferences(DEFAULT_PREFERENCES), migrated: false };
  }
  const prefs: ThemePreferences = { ...DEFAULT_PREFERENCES };
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
  return { settings: settingsFromPreferences(prefs), migrated };
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
 * 写入版本标记 schemaVersion，并派生 @deprecated 扁平投影（theme=mode 的 dark 侧近似、
 * accent/density/motion 直映射）。system 模式投影取 'dark'（与 index.html 首绘默认一致；
 * 端侧精确解析属消费方职责）。投影仅供 mobile 过渡兼容（TL 裁决：物理删除归 THEME-3），
 * preferences 仍是唯一事实源——所有投影随本函数单向再生成，无独立写路径。
 */
export function settingsFromPreferences(preferences: ThemePreferences): Settings {
  return {
    schemaVersion: PREFERENCES_VERSION,
    preferences,
    theme: preferences.mode === 'light' ? 'light' : 'dark',
    accent: preferences.accent,
    density: preferences.density,
    motion: preferences.motionEnabled,
  };
}
