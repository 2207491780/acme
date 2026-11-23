/**
 * REQ-20261121-043502「外观能力包」AC-1~AC-4：core 偏好引擎 v3（src/preferences.ts）+
 * radius/语义色解析（src/theme.ts）契约用例。
 *
 * 表面 = 契约「确认记录②~⑥/AC-1~AC-4」+ TL 钉死事实（导出面，勿臆造）：
 *   PREFERENCES_VERSION = 3；ThemePreferences v3 新增字段（确认记录②）：
 *     radius: 'small'|'medium'|'large'|'full'（默认 'medium'=现状基线）；
 *     grayMode / weakMode: boolean（默认 false）；
 *     contentWidth: 'fluid'|'compact'（默认 'fluid'）；
 *     semanticColors: { success: string|null; warning: string|null; danger: string|null }（默认全 null=TONES 基线）；
 *   migrateSettings：v2→v3 补默认无损、v1（legacy 扁平，无版本标记）→v3 直迁、blob 内嵌 schemaVersion 同步=3；
 *   validateThemePreferences：v3 字段逐项校验非法降级默认，不抛错；
 *   resolveRadiusTokens(radius) → AntD token 值（medium=现状基线，full=胶囊圆角，small/large 按档位缩放）；
 *   resolveSemanticColors(overrides) → null 回 TONES 基线、合法 hex 覆盖。
 *
 * 装配照 THEME-1 范本 REQ20261121010725-preferences.test.ts：node 环境纯函数单测。
 * THEME-3（REQ-20261121-200415 ⑧）：4 个 @deprecated 投影字段已物理删除，本文件断言同步改为零残留。
 *
 * 注：buildTheme/themeSnapshot 的 token 消费面在 web（契约变更清单归属），集成断言见
 * web/tests/REQ20261121043502-appearanceDrawer.test.tsx（AC-3web/AC-5/AC-7 用例）。
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  DENSITY,
  PREFERENCES_VERSION,
  TONES,
  migrateSettings,
  resolveRadiusTokens,
  resolveSemanticColors,
  validateThemePreferences,
  type ThemePreferences,
} from '../src';

/** v3 默认值（TL 钉死面：medium/false/false/fluid/语义色全 null）。 */
const V3_DEFAULTS = {
  radius: 'medium' as const,
  grayMode: false,
  weakMode: false,
  contentWidth: 'fluid' as const,
  semanticColors: { success: null, warning: null, danger: null },
};

/** v4 默认值（REQ-20261122-233427 确认②：sidebar/line/auto/顶栏全显=现状外观）。 */
const V4_DEFAULTS = {
  layoutMode: 'sidebar' as const,
  tabStyle: 'line' as const,
  siderTone: 'auto' as const,
  topbarWidgets: {
    search: true, bell: true, lang: true, timezone: true, appearance: true,
    themeToggle: true, lock: true, fullscreen: true, settings: true, release: true,
  },
};

/** 合法基线偏好（v2 五字段 + v3 五字段 + v4 四字段；over 允许注入非法值供降级用例使用）。 */
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
  ...V4_DEFAULTS,
  ...(over as Partial<ThemePreferences>),
});

/** 当前版本 settings 形状（settings 内嵌 preferences + 版本标记，THEME-1 确认记录③）。 */
const defaultSettingsShape = (): { schemaVersion: number; preferences: ThemePreferences } => ({
  schemaVersion: PREFERENCES_VERSION,
  preferences: { ...DEFAULT_PREFERENCES },
});

/**
 * THEME-3 适配口径（REQ-20261121-200415 确认记录⑧）：4 个 @deprecated 投影字段已物理删除，
 * Settings = { schemaVersion, preferences }（preferences 唯一事实源）。
 * 原「投影存在且一致」断言改为「投影零残留」。
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

describe('REQ20261121043502 core 偏好引擎 v3 schema（AC-1/AC-2）', () => {
  it('should_expose_v3_defaults_when_version_bumped_to_three', () => {
    // 版本与默认值（TL 钉死面：v2 五字段不变 + v3 五字段默认 + v4 四字段默认=现状外观）
    expect(PREFERENCES_VERSION).toBe(4);
    expect(DEFAULT_PREFERENCES).toEqual({
      mode: 'light',
      accent: 'cyan',
      customAccent: null,
      density: 'comfortable',
      motionEnabled: true,
      ...V3_DEFAULTS,
      ...V4_DEFAULTS,
    });

    // 合法 v3 全量输入归一化通过
    expect(
      validateThemePreferences(
        validPreferences({ radius: 'full', grayMode: true, weakMode: true, contentWidth: 'compact' }),
      ),
    ).toEqual(
      validPreferences({ radius: 'full', grayMode: true, weakMode: true, contentWidth: 'compact' }),
    );
    // 缺省字段补默认（含 v3 字段）
    expect(validateThemePreferences({})).toEqual({ ...DEFAULT_PREFERENCES });
  });

  it('should_migrate_v2_preferences_to_v3_losslessly_when_version_missing', () => {
    // v2 blob（schemaVersion=2 + 五字段 preferences）→ 当前版本：旧字段无损、新版本字段补默认
    const v2Blob = {
      schemaVersion: 2,
      preferences: {
        mode: 'dark',
        accent: 'violet',
        customAccent: '#b08968',
        density: 'compact',
        motionEnabled: false,
      },
    };
    const migrated = migrateSettings(v2Blob);
    expect(migrated.migrated).toBe(true);
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(migrated.settings.preferences).toEqual({
      mode: 'dark',
      accent: 'violet',
      customAccent: '#b08968',
      density: 'compact',
      motionEnabled: false,
      ...V3_DEFAULTS,
      ...V4_DEFAULTS,
    });
    // 投影字段零残留（THEME-3 ⑧）
    expectNoProjections(migrated.settings as unknown as SettingsWithProjections, migrated.settings.preferences);

    // v2 preferences 缺字段：present 无损保留、缺失补 v3/v2 默认
    // （fixture 用 dark：THEME-3 ⑧ 后无投影，mode 三态经 preferences 直存）
    const partialV2 = migrateSettings({ schemaVersion: 2, preferences: { mode: 'dark', accent: 'ruby' } });
    expect(partialV2.migrated).toBe(true);
    expect(partialV2.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(partialV2.settings.preferences).toEqual({
      mode: 'dark',
      accent: 'ruby',
      customAccent: null,
      density: 'comfortable',
      motionEnabled: true,
      ...V3_DEFAULTS,
      ...V4_DEFAULTS,
    });
    expectNoProjections(partialV2.settings as unknown as SettingsWithProjections, partialV2.settings.preferences);
  });

  it('should_migrate_v1_legacy_flat_settings_directly_to_v3_when_no_version_present', () => {
    // v1（legacy 扁平无版本，THEME-1 前语义：dark/accent/density/motion）→ 当前版本直迁
    const legacy = migrateSettings({ dark: true, accent: 'violet', density: 'compact', motion: false });
    expect(legacy.migrated).toBe(true);
    expect(legacy.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(legacy.settings.preferences).toEqual({
      mode: 'dark',
      accent: 'violet',
      customAccent: null,
      density: 'compact',
      motionEnabled: false,
      ...V3_DEFAULTS,
      ...V4_DEFAULTS,
    });
    expectNoProjections(legacy.settings as unknown as SettingsWithProjections, legacy.settings.preferences);

    // dark:false → light 直迁
    const lightLegacy = migrateSettings({ dark: false, accent: 'cyan', density: 'comfortable', motion: true });
    expect(lightLegacy.migrated).toBe(true);
    expect(lightLegacy.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(lightLegacy.settings.preferences).toEqual({
      mode: 'light', accent: 'cyan', customAccent: null, density: 'comfortable', motionEnabled: true,
      ...V3_DEFAULTS,
      ...V4_DEFAULTS,
    });

    // 带 schemaVersion:1 标记的扁平输入：直迁 v3 不抛错（宽容口径：标记变体不阻断迁移）
    expect(() => migrateSettings({ schemaVersion: 1, dark: true, accent: 'sky', density: 'compact', motion: true })).not.toThrow();
  });

  it('should_degrade_invalid_v3_fields_when_validated', () => {
    // 未知 radius 档 → medium，其余合法字段保留
    expect(validateThemePreferences(validPreferences({ radius: 'huge' }))).toEqual(validPreferences({ radius: 'medium' }));
    expect(validateThemePreferences(validPreferences({ radius: 42 }))).toEqual(validPreferences({ radius: 'medium' }));

    // grayMode / weakMode 非布尔 → false
    expect(validateThemePreferences(validPreferences({ grayMode: 'yes' }))).toEqual(validPreferences({ grayMode: false }));
    expect(validateThemePreferences(validPreferences({ grayMode: 1 }))).toEqual(validPreferences({ grayMode: false }));
    expect(validateThemePreferences(validPreferences({ weakMode: 0 }))).toEqual(validPreferences({ weakMode: false }));
    expect(validateThemePreferences(validPreferences({ weakMode: 'x' }))).toEqual(validPreferences({ weakMode: false }));

    // 未知 contentWidth → fluid
    expect(validateThemePreferences(validPreferences({ contentWidth: 'wide' }))).toEqual(validPreferences({ contentWidth: 'fluid' }));
    expect(validateThemePreferences(validPreferences({ contentWidth: 7 }))).toEqual(validPreferences({ contentWidth: 'fluid' }));

    // semanticColors 非法 hex 逐字段降级 null（同 customAccent 的 #RRGGBB 口径）
    expect(
      validateThemePreferences(validPreferences({ semanticColors: { success: 'b08968', warning: '#12345', danger: '#gggggg' } })),
    ).toEqual(validPreferences({ semanticColors: { success: null, warning: null, danger: null } }));
    expect(
      validateThemePreferences(validPreferences({ semanticColors: { success: 42, warning: null, danger: true } })),
    ).toEqual(validPreferences({ semanticColors: { success: null, warning: null, danger: null } }));
    // 合法 hex 覆盖保留
    expect(
      validateThemePreferences(validPreferences({ semanticColors: { success: '#22c55e', warning: '#d4a017', danger: null } })),
    ).toEqual(validPreferences({ semanticColors: { success: '#22c55e', warning: '#d4a017', danger: null } }));
    // semanticColors 整体非法（非对象/缺字段）→ 回默认三元组，不抛错
    expect(validateThemePreferences(validPreferences({ semanticColors: 42 }))).toEqual(validPreferences());
    expect(validateThemePreferences(validPreferences({ semanticColors: 'x' }))).toEqual(validPreferences());

    // 混合输入：v3 非法字段逐项降级、不覆盖既有合法字段、不抛错
    let result: ThemePreferences | undefined;
    expect(() => {
      result = validateThemePreferences(
        validPreferences({ radius: 'nano', grayMode: 'on', contentWidth: 'wide', semanticColors: { success: 'zzz' } }),
      );
    }).not.toThrow();
    expect(result).toEqual(validPreferences());
  });
});

describe('REQ20261121043502 radius 档位映射（AC-3）', () => {
  it('should_apply_radius_and_semantic_overrides_when_building_theme', () => {
    // 四档均产出 AntD token 数值（medium=现状基线 = DENSITY.comfortable.radius，THEME-1 已锁定 12）
    const radiusKeys = ['small', 'medium', 'large', 'full'] as const;
    for (const key of radiusKeys) {
      expect(typeof resolveRadiusTokens(key), `radius=${key} 应产出 token 数值`).toBe('number');
    }
    // medium=现状基线（RADII 现值不动，确认记录③）
    expect(resolveRadiusTokens('medium')).toBe(DENSITY.comfortable.radius);
    // 档位单调：small < medium < large < full（full=胶囊统一大圆角，为本组上界）
    expect(resolveRadiusTokens('small')).toBeLessThan(resolveRadiusTokens('medium'));
    expect(resolveRadiusTokens('medium')).toBeLessThan(resolveRadiusTokens('large'));
    expect(resolveRadiusTokens('large')).toBeLessThan(resolveRadiusTokens('full'));
    expect(resolveRadiusTokens('full')).toBe(
      Math.max(...radiusKeys.map((key) => resolveRadiusTokens(key))),
    );

    // 语义色覆盖：warning 自定义生效、success/danger 默认项不变（null 回 TONES 基线）
    const resolved = resolveSemanticColors({ success: null, warning: '#d4a017', danger: null });
    expect(resolved.success, 'success 未覆盖应回 TONES 基线（默认项不变）').toBe(TONES.success);
    expect(String(resolved.warning).toLowerCase(), 'warning 自定义值应生效').toBe('#d4a017');
    expect(resolved.danger, 'danger 未覆盖应回 TONES 基线（默认项不变）').toBe(TONES.danger);
  });
});

describe('REQ20261121043502 语义色解析回退（AC-4）', () => {
  it('should_fall_back_to_default_semantics_when_overrides_null', () => {
    // 全 null → TONES 基线三元组
    expect(resolveSemanticColors({ success: null, warning: null, danger: null })).toEqual({
      success: TONES.success,
      warning: TONES.warning,
      danger: TONES.danger,
    });
    // 缺键/空对象/空值输入 → 同样回默认（null=用默认，确认记录⑤）
    expect(resolveSemanticColors({})).toEqual({ success: TONES.success, warning: TONES.warning, danger: TONES.danger });
    expect(resolveSemanticColors(null)).toEqual({ success: TONES.success, warning: TONES.warning, danger: TONES.danger });
    // 部分覆盖：仅 danger 自定义，其余保持基线
    expect(resolveSemanticColors({ danger: '#c2410c' })).toEqual({
      success: TONES.success,
      warning: TONES.warning,
      danger: '#c2410c',
    });
  });
});

describe('REQ20261121043502 当前版本 blob 不重复迁移（迁移口径回归）', () => {
  it('should_keep_current_v3_settings_without_migration_when_version_current', () => {
    // 已是当前版本：preferences 原样保留，不重复迁移
    const current = defaultSettingsShape();
    const result = migrateSettings(current);
    expect(result.migrated).toBe(false);
    expect(result.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(result.settings.preferences).toEqual(current.preferences);
    expectNoProjections(result.settings as unknown as SettingsWithProjections, result.settings.preferences);

    // v3 blob 带非法字段（历史脏数据）：迁移路径兜底归一，不抛错
    const dirty = migrateSettings({
      schemaVersion: 3,
      preferences: { ...DEFAULT_PREFERENCES, radius: 'giant', semanticColors: { success: 'no-hash' } },
    });
    expect(() => dirty).not.toThrow();
    expect(dirty.settings.preferences.radius).toBe('medium');
    expect(dirty.settings.preferences.semanticColors).toEqual({ success: null, warning: null, danger: null });
  });
});
