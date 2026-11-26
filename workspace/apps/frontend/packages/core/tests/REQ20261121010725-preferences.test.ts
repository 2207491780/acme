/**
 * REQ-20261121-010725 AC-1~AC-4：core 主题偏好引擎（src/preferences.ts）契约用例。
 *
 * 表面 = 契约「确认记录/交互规格/AC」+ TL 钉死事实（导出面，勿臆造）：
 *   PREFERENCES_VERSION = 2；ThemeMode = 'light'|'dark'|'system'；Density = 'comfortable'|'compact'；
 *   ThemePreferences { mode; accent; customAccent; density; motionEnabled }；
 *   DEFAULT_PREFERENCES（light/cyan/null/comfortable/true）；
 *
 * REQ-20261121-043502（AC-10 回归口径适配）：偏好模型 v3（PREFERENCES_VERSION 2→3，
 * 新字段 radius/grayMode/weakMode/contentWidth/semanticColors 默认 medium/false/false/fluid/全 null）。
 * 本文件按 v3 迁移语义适配：版本断言 3、DEFAULT_PREFERENCES/fixture 补 v3 字段、
 * 迁移产物 schemaVersion=3（v1 扁平直迁 v3）；用例结构与 THEME-1 断言不变。
 * v3 新字段的专项用例见 REQ20261121043502-appearance.test.ts。
 *   validateThemePreferences(input: unknown)：逐字段校验非法降级，不抛错；
 *   migrateSettings(raw: unknown): { settings; migrated }（legacy 无版本 settings：
 *     dark:true→mode 'dark'、其余字段无损映射、写版本标记；非法输入返回默认）；
 *   resolveThemeMode(mode, systemPrefersDark)；resolveAccentColor(p)（customAccent 优先，否则 ACCENTS[accent]）。
 *
 * 装配：node 环境纯函数单测（core vitest 默认 node，无需 jsdom）。
 * legacy fixture 字面取自契约 AC-3/确认记录③④（dark/accent/density/motion 四字段旧语义）。
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  migrateSettings,
  resolveAccentColor,
  resolveThemeMode,
  validateThemePreferences,
  type ThemePreferences,
} from '../src';

/** 合法基线偏好（可按字段覆盖；over 允许注入非法值供降级用例使用）。v3/v4 字段补默认（043502/233427 口径）。 */
const validPreferences = (over: Partial<ThemePreferences> | Record<string, unknown> = {}): ThemePreferences => ({
  mode: 'dark',
  accent: 'emerald',
  customAccent: '#b08968',
  density: 'compact',
  motionEnabled: false,
  radius: 'medium',
  grayMode: false,
  weakMode: false,
  contentWidth: 'fluid',
  semanticColors: { success: null, warning: null, danger: null },
  layoutMode: 'sidebar',
  tabStyle: 'line',
  siderTone: 'auto',
  topbarWidgets: {
    search: true, bell: true, lang: true, timezone: true, appearance: true,
    themeToggle: true, lock: true, fullscreen: true, settings: true,
  },
  ...(over as Partial<ThemePreferences>),
});

/** 当前版本 settings 形状（settings 内嵌 preferences + 版本标记，契约确认记录③）。 */
const defaultSettingsShape = (): { schemaVersion: number; preferences: ThemePreferences } => ({
  schemaVersion: PREFERENCES_VERSION,
  preferences: { ...DEFAULT_PREFERENCES },
});

/**
 * THEME-3 适配口径（REQ-20261121-200415 确认记录⑧）：4 个 @deprecated 投影字段
 * （theme/accent/density/motion）已物理删除，Settings = { schemaVersion, preferences }
 * （preferences 唯一事实源）。原「投影存在且一致」断言改为「投影零残留」。
 */
type SettingsWithProjections = {
  schemaVersion?: number;
  preferences?: ThemePreferences;
  theme?: unknown;
  accent?: unknown;
  density?: unknown;
  motion?: unknown;
};

const expectNoProjections = (
  settings: SettingsWithProjections,
  _prefs?: ThemePreferences,
): void => {
  for (const key of ['theme', 'accent', 'density', 'motion'] as const) {
    expect(key in settings, `投影字段 ${key} 删除后不得残留（THEME-3 确认记录⑧）`).toBe(false);
  }
};

describe('REQ20261121010725 core 偏好引擎 schema（AC-1/AC-2）', () => {
  it('should_normalize_valid_preferences_when_parsed', () => {
    // 版本与默认值常量（TL 钉死面；043502 v3 + 233427 v4 适配：版本 4 + 新字段默认=现状外观）
    expect(PREFERENCES_VERSION).toBe(4);
    expect(DEFAULT_PREFERENCES).toEqual({
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
      layoutMode: 'sidebar',
      tabStyle: 'line',
      siderTone: 'auto',
      topbarWidgets: {
        search: true, bell: true, lang: true, timezone: true, appearance: true,
        themeToggle: true, lock: true, fullscreen: true, settings: true,
      },
    });

    // 合法全量输入：三态 mode + 合法 hex 色值 + 密度 + 动效 → 归一化通过且输出类型正确
    expect(validateThemePreferences(validPreferences())).toEqual(validPreferences());
    expect(validateThemePreferences(validPreferences({ mode: 'system' }))).toEqual(
      validPreferences({ mode: 'system' }),
    );
    expect(validateThemePreferences(validPreferences({ customAccent: '#6aa8cf' }))).toEqual(
      validPreferences({ customAccent: '#6aa8cf' }),
    );

    // 缺省字段补默认（归一化含补全）
    expect(validateThemePreferences({})).toEqual({ ...DEFAULT_PREFERENCES });
  });

  it('should_fallback_to_defaults_when_preferences_invalid', () => {
    // 整体非法（非对象/垃圾值/字段全非法）：不抛错且整体回默认
    const garbageInputs: unknown[] = [null, undefined, 42, 'x', true, [], { mode: 1 }, { mode: null }];
    for (const garbage of garbageInputs) {
      let result: ThemePreferences | undefined;
      expect(() => {
        result = validateThemePreferences(garbage);
      }).not.toThrow();
      expect(result).toEqual({ ...DEFAULT_PREFERENCES });
    }
  });

  it('should_degrade_only_invalid_fields_when_partial_preferences_invalid', () => {
    // 未知 mode → light，其余合法字段保留
    expect(validateThemePreferences(validPreferences({ mode: 'sepia' }))).toEqual(
      validPreferences({ mode: 'light' }),
    );
    // 非法色值格式（缺 # / 位数不足 / 非法字符 / 非字符串）→ customAccent 置 null，其余保留
    expect(validateThemePreferences(validPreferences({ customAccent: 'b08968' }))).toEqual(
      validPreferences({ customAccent: null }),
    );
    expect(validateThemePreferences(validPreferences({ customAccent: '#12345' }))).toEqual(
      validPreferences({ customAccent: null }),
    );
    expect(validateThemePreferences(validPreferences({ customAccent: '#gggggg' }))).toEqual(
      validPreferences({ customAccent: null }),
    );
    expect(validateThemePreferences(validPreferences({ customAccent: 42 }))).toEqual(
      validPreferences({ customAccent: null }),
    );
    // 未知 accent 键 → cyan，其余保留
    expect(validateThemePreferences(validPreferences({ accent: 'purple' }))).toEqual(
      validPreferences({ accent: 'cyan' }),
    );
    expect(validateThemePreferences(validPreferences({ accent: 7 }))).toEqual(
      validPreferences({ accent: 'cyan' }),
    );
    // 未知密度 → comfortable
    expect(validateThemePreferences(validPreferences({ density: 'cozy' }))).toEqual(
      validPreferences({ density: 'comfortable' }),
    );
    // 动效非布尔 → true
    expect(validateThemePreferences(validPreferences({ motionEnabled: 'yes' }))).toEqual(
      validPreferences({ motionEnabled: true }),
    );
    // 混合输入：非法字段逐项降级、不覆盖既有合法字段
    expect(
      validateThemePreferences(
        validPreferences({ mode: 'dark', accent: 'unknown-key', customAccent: '#b08968', density: 'compact', motionEnabled: false }),
      ),
    ).toEqual(validPreferences({ accent: 'cyan' }));
  });
});

describe('REQ20261121010725 legacy 迁移（AC-3）', () => {
  it('should_migrate_legacy_settings_losslessly_when_version_missing', () => {
    // 旧语义四字段（契约 AC-3/确认记录③④）：dark:true → mode 'dark'，accent/density/motion 无损映射
    const legacy = { dark: true, accent: 'violet', density: 'compact', motion: false };
    const migrated = migrateSettings(legacy);
    expect(migrated.migrated).toBe(true);
    // 版本标记写入 + 新 shape（内嵌 preferences）逐字段映射正确（043502/233427：v1 扁平直迁当前版本，新字段补默认）
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(migrated.settings.preferences).toEqual({
      mode: 'dark', accent: 'violet', customAccent: null, density: 'compact', motionEnabled: false,
      radius: 'medium', grayMode: false, weakMode: false, contentWidth: 'fluid',
      semanticColors: { success: null, warning: null, danger: null },
      layoutMode: 'sidebar', tabStyle: 'line', siderTone: 'auto',
      topbarWidgets: {
        search: true, bell: true, lang: true, timezone: true, appearance: true,
        themeToggle: true, lock: true, fullscreen: true, settings: true,
      },
    });
    // 无损：投影字段零残留（THEME-3 ⑧：Settings 仅 schemaVersion+preferences）
    expectNoProjections(migrated.settings as unknown as SettingsWithProjections, migrated.settings.preferences);

    // dark:false → mode 'light'
    const lightLegacy = migrateSettings({ dark: false, accent: 'cyan', density: 'comfortable', motion: true });
    expect(lightLegacy.migrated).toBe(true);
    expect(lightLegacy.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(lightLegacy.settings.preferences).toEqual({
      mode: 'light', accent: 'cyan', customAccent: null, density: 'comfortable', motionEnabled: true,
      radius: 'medium', grayMode: false, weakMode: false, contentWidth: 'fluid',
      semanticColors: { success: null, warning: null, danger: null },
      layoutMode: 'sidebar', tabStyle: 'line', siderTone: 'auto',
      topbarWidgets: {
        search: true, bell: true, lang: true, timezone: true, appearance: true,
        themeToggle: true, lock: true, fullscreen: true, settings: true,
      },
    });
    expectNoProjections(lightLegacy.settings as unknown as SettingsWithProjections, lightLegacy.settings.preferences);
  });

  it('should_return_defaults_without_throwing_when_migrate_input_invalid', () => {
    // 非法输入（含 null）返回默认且不抛错（委派单钉死：null 输入不抛）
    const garbageInputs: unknown[] = [null, undefined, 42, '{broken', []];
    for (const garbage of garbageInputs) {
      expect(() => migrateSettings(garbage)).not.toThrow();
      const result = migrateSettings(garbage).settings;
      expect(result.schemaVersion).toBe(PREFERENCES_VERSION);
      expect(result.preferences).toEqual({ ...DEFAULT_PREFERENCES });
      expectNoProjections(result as unknown as SettingsWithProjections, result.preferences);
    }
  });

  it('should_keep_current_settings_without_migration_when_version_current', () => {
    // 已是当前版本：preferences 原样保留，不重复迁移（043502/233427：当前版本=PREFERENCES_VERSION）
    const current = defaultSettingsShape();
    const result = migrateSettings(current);
    expect(result.migrated).toBe(false);
    expect(result.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(result.settings.preferences).toEqual(current.preferences);
    expectNoProjections(result.settings as unknown as SettingsWithProjections, result.settings.preferences);
  });
});

describe('REQ20261121010725 system 模式解析与取色（AC-4）', () => {
  it('should_follow_system_scheme_when_mode_is_system', () => {
    // system × 系统真假矩阵：跟随 prefers-color-scheme
    expect(resolveThemeMode('system', true)).toBe('dark');
    expect(resolveThemeMode('system', false)).toBe('light');
    // mode ≠ system：系统事件不改变用户选择
    expect(resolveThemeMode('light', true)).toBe('light');
    expect(resolveThemeMode('light', false)).toBe('light');
    expect(resolveThemeMode('dark', true)).toBe('dark');
    expect(resolveThemeMode('dark', false)).toBe('dark');
  });

  it('should_resolve_accent_color_from_custom_or_preset_when_called', () => {
    // customAccent 优先
    expect(resolveAccentColor(validPreferences({ customAccent: '#b08968' }))).toBe('#b08968');
    // 无自定义时回预设键值
    expect(resolveAccentColor(validPreferences({ customAccent: null }))).toBe('#58aa82');
    // 默认 cyan
    expect(resolveAccentColor({ ...DEFAULT_PREFERENCES })).toBe('#5aa7a0');
  });
});
