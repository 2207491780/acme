/**
 * REQ-20261122-233427（THEME-4）AC-1：core 偏好 schema v4 契约用例。
 *
 * 表面 = 契约确认记录② + AC-1 + 委派单钉死事实（导出面勿臆造）：
 *   PREFERENCES_VERSION 3→4；新增字段：
 *   - layoutMode('sidebar'|'top'|'mixed'，缺省 sidebar)；
 *   - tabStyle('line'|'card'|'chrome'|'plain'，缺省 line=现行为)；
 *   - siderTone('auto'|'dark'，缺省 auto=现行为)；
 *   - topbarWidgets(Record<WidgetKey,boolean>，WidgetKey 枚举=契约②原文
 *     search/bell/lang/timezone/appearance/themeToggle/lock/fullscreen/settings（release 键 legacy 容忍、产物不输出），
 *     缺省全显=现状逐字)；
 *   migrate v3→v4 无损（既有字段逐字保留，新字段补默认值）；非法值按既有口径逐字段降级不抛错；
 *   buildSettings 投影 = { schemaVersion, preferences }（msm preferences PUT 载荷同形基础）。
 *
 * 装配：node 环境纯函数单测（core vitest 缺省 node，010725/200415 范本口径）。
 * 阶段状态：实现（preferences.ts v4）交付前运行 → v4 版本/默认值/迁移断言红（等实现）；
 *   迁移窗口结构性口径（extensions 保留、非法输入回默认不抛错）沿用既有语义先绿。
 *   注意 registerPreferenceExtension 注册表为文件级全局：current-version 直通用例排在
 *   extensions 注册用例之前（200415 范本同款次序防物化串扰）。
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  buildSettings,
  migrateSettings,
  registerPreferenceExtension,
  validateThemePreferences,
  type ThemePreferences,
} from '../src';

/** WidgetKey 枚举（REQ-20261126-125836 删除 release UI 呈现后为九键；legacy release 键接受但忽略）。 */
const WIDGET_KEYS = [
  'search', 'bell', 'lang', 'timezone', 'appearance',
  'themeToggle', 'lock', 'fullscreen', 'settings',
] as const;

/** topbarWidgets 缺省全显（=现状逐字，契约②钉死）。 */
const allWidgetsVisible = (): Record<string, boolean> =>
  Object.fromEntries(WIDGET_KEYS.map((key) => [key, true]));

/** v4 全字段偏好缺省形（v3 缺省 + v4 新字段缺省；DEFAULT_PREFERENCES 断言基线）。 */
const v4DefaultPreferences = (): Record<string, unknown> => ({
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
  topbarWidgets: allWidgetsVisible(),
});

/** v3 非默认值样本（无损断言基线：既有字段逐字保留的「逐字」载体）。 */
const v3PreferencesSample = (): Record<string, unknown> => ({
  mode: 'dark',
  accent: 'violet',
  customAccent: '#b08968',
  density: 'compact',
  motionEnabled: false,
  radius: 'full',
  grayMode: true,
  weakMode: true,
  contentWidth: 'compact',
  semanticColors: { success: '#22c55e', warning: '#c2410c', danger: null },
});

/** v3 基线 blob（schemaVersion=3 + v3 字段集，无 v4 字段）。 */
const v3Blob = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  schemaVersion: 3,
  preferences: { ...v3PreferencesSample(), ...over },
});

describe('REQ20261122233427 v4 schema 版本与缺省形状（AC-1）', () => {
  it('should_pin_v4_version_and_default_preferences_shape_when_schema_bumped', () => {
    // 版本推进（契约②：PREFERENCES_VERSION 3→4）
    expect(PREFERENCES_VERSION).toBe(4);
    // DEFAULT_PREFERENCES 完整 v4 形状（v3 缺省逐字 + v4 新字段缺省）
    expect(DEFAULT_PREFERENCES).toEqual(v4DefaultPreferences());
    // 既有 v3 缺省逐字不变（防 v4 扩展破坏 v3 缺省面）
    expect(DEFAULT_PREFERENCES).toMatchObject({
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
    });
    // topbarWidgets 键域=契约②原文、值域=全布尔真
    const widgetsRaw = (DEFAULT_PREFERENCES as unknown as Record<string, unknown>)['topbarWidgets'];
    expect(widgetsRaw, 'DEFAULT_PREFERENCES 应内嵌 topbarWidgets').toBeTruthy();
    const widgets = widgetsRaw as Record<string, unknown>;
    expect(Object.keys(widgets).sort()).toEqual([...WIDGET_KEYS].sort());
    expect(Object.values(widgets).every((v) => v === true)).toBe(true);
  });
});

describe('REQ20261122233427 当前版本 blob 直通（AC-1 迁移窗口边界）', () => {
  it('should_keep_current_v4_blob_without_migration_when_version_current', () => {
    // 先于 extensions 注册用例执行（注册表为文件级全局，防 defaults 物化串扰——200415 范本次序）
    const current = {
      schemaVersion: 4,
      preferences: v4DefaultPreferences() as unknown as ThemePreferences,
    };
    const result = migrateSettings(current);

    expect(result.migrated).toBe(false);
    expect(result.settings.schemaVersion).toBe(4);
    expect(result.settings.preferences).toEqual(current.preferences);
  });
});

describe('REQ20261122233427 v3→v4 迁移无损（AC-1）', () => {
  it('should_migrate_v3_blob_to_v4_losslessly_with_new_field_defaults', () => {
    const migrated = migrateSettings(v3Blob());

    expect(migrated.migrated).toBe(true);
    expect(migrated.settings.schemaVersion).toBe(4);
    // 无损：既有 v3 字段逐字保留（语义色键序无关的结构化断言）
    expect(migrated.settings.preferences).toMatchObject(v3PreferencesSample());
    // 新字段补默认值
    expect(migrated.settings.preferences).toMatchObject({
      layoutMode: 'sidebar',
      tabStyle: 'line',
      siderTone: 'auto',
      topbarWidgets: allWidgetsVisible(),
    });
  });

  it('should_preserve_extensions_payload_when_migrating_v3_blob', () => {
    // 迁移无损窗口覆盖 extensions（含未注册域）：blob 存储内容不得在迁移中丢失（200415 口径）。
    registerPreferenceExtension('v4MigrationProbe', {
      defaults: { on: false },
      parse: (raw: unknown) => raw,
    });
    const foreign = { anyShape: [1, 'x', { deep: true }] };
    const blob = v3Blob({
      extensions: { v4MigrationProbe: { on: true }, foreignDomain: foreign },
    });
    const migrated = migrateSettings(blob);
    const extensionsRaw = (migrated.settings.preferences as unknown as Record<string, unknown>)['extensions'];
    const extensions = extensionsRaw as Record<string, unknown>;

    expect(extensions['v4MigrationProbe']).toEqual({ on: true });
    expect(extensions['foreignDomain']).toEqual(foreign);
    // 核心偏好字段无损
    expect(migrated.settings.preferences).toMatchObject(v3PreferencesSample());
    expect(migrated.settings.schemaVersion).toBe(4);
  });

  it('should_keep_explicit_v4_values_and_fill_only_missing_fields_when_v3_blob_carries_v4_fields', () => {
    // 前向写入窗口：v3 版本 blob 已带部分 v4 字段 → 显式值保留、缺失字段补默认
    const partial = v3Blob({
      layoutMode: 'top',
      siderTone: 'dark',
      topbarWidgets: { search: false },
    });
    const migrated = migrateSettings(partial);
    const prefs = migrated.settings.preferences as unknown as Record<string, unknown>;

    expect(migrated.migrated).toBe(true);
    expect(migrated.settings.schemaVersion).toBe(4);
    expect(prefs['layoutMode']).toBe('top');
    expect(prefs['siderTone']).toBe('dark');
    expect(prefs['tabStyle']).toBe('line'); // 缺失 → 补默认
    // 显式 topbarWidgets 值保留；键域不越界（逐键回填粒度为实现自验轮校准点，此处钉键域/值域不变式）
    const widgets = prefs['topbarWidgets'] as Record<string, unknown>;
    expect(widgets['search']).toBe(false);
    expect(Object.keys(widgets).every((key) => (WIDGET_KEYS as readonly string[]).includes(key))).toBe(true);
    expect(Object.values(widgets).every((v) => typeof v === 'boolean')).toBe(true);
  });
});

describe('REQ20261122233427 v4 非法值处理（AC-1 既有降级口径）', () => {
  it('should_degrade_invalid_v4_fields_per_field_when_validating', () => {
    // 既有口径（010725）：validateThemePreferences 逐字段校验非法降级、不抛错
    const bad = v4DefaultPreferences({
      layoutMode: 'float',
      tabStyle: 'ribbon',
      siderTone: 'midnight',
      topbarWidgets: 42,
    });
    let normalized: Record<string, unknown> | undefined;
    expect(() => {
      normalized = validateThemePreferences(bad as unknown as ThemePreferences) as unknown as Record<string, unknown>;
    }).not.toThrow();

    expect(normalized?.['layoutMode']).toBe('sidebar');
    expect(normalized?.['tabStyle']).toBe('line');
    expect(normalized?.['siderTone']).toBe('auto');
    // 整体非法 topbarWidgets → 全显缺省回退
    expect(normalized?.['topbarWidgets']).toEqual(allWidgetsVisible());
    // 合法字段不受损（逐字段降级只动非法项）
    expect(normalized?.['mode']).toBe('light');
    expect(normalized?.['accent']).toBe('cyan');
  });

  it('should_keep_topbar_widgets_domain_invariant_when_partial_payload_invalid', () => {
    // 部分 v4 载荷含未知 WidgetKey/非布尔值 → 键域不越界（⊆契约②枚举）、值域全布尔；
    // 未知键剔除或整体回退均为合规实现（策略粒度实现自验轮校准），此处钉不变式
    const partial = v4DefaultPreferences({
      topbarWidgets: { search: false, rogueKey: true, bell: 'yes' },
    });
    let normalized: Record<string, unknown> | undefined;
    expect(() => {
      normalized = validateThemePreferences(partial as unknown as ThemePreferences) as unknown as Record<string, unknown>;
    }).not.toThrow();

    const widgets = normalized?.['topbarWidgets'] as Record<string, unknown> | undefined;
    expect(widgets, '规范化后 topbarWidgets 应为对象').toBeTruthy();
    expect(Object.keys(widgets as Record<string, unknown>).every(
      (key) => (WIDGET_KEYS as readonly string[]).includes(key),
    ), 'topbarWidgets 键域不得越出契约②枚举').toBe(true);
    expect(Object.values(widgets as Record<string, unknown>).every(
      (v) => typeof v === 'boolean',
    ), 'topbarWidgets 值域必须全布尔').toBe(true);
  });
});

describe('REQ20261122233427 v4 投影与非法输入兜底（AC-1 + msm 载荷同形基础）', () => {
  it('should_project_v4_settings_shape_via_buildsettings_when_called', () => {
    // buildSettings 投影 = { schemaVersion, preferences }（契约背景钉死；msm/preferences PUT 载荷={schemaVersion, preferences} 同形）
    const prefs = v4DefaultPreferences({ layoutMode: 'mixed', tabStyle: 'chrome' }) as unknown as ThemePreferences;
    const projection = buildSettings(prefs) as unknown as {
      schemaVersion: number;
      preferences: unknown;
    };

    expect(projection).toEqual({ schemaVersion: PREFERENCES_VERSION, preferences: prefs });
    expect(Object.keys(projection).sort()).toEqual(['preferences', 'schemaVersion']);
  });

  it('should_return_v4_defaults_without_throwing_when_migrate_input_invalid', () => {
    // 非法输入（含 null）返回 v4 默认且不抛错（010725 口径延续，版本水位抬到 v4）
    const garbageInputs: unknown[] = [null, undefined, 42, '{broken', []];
    for (const garbage of garbageInputs) {
      expect(() => migrateSettings(garbage)).not.toThrow();
      const result = migrateSettings(garbage).settings;
      expect(result.schemaVersion).toBe(4);
      expect(result.preferences).toEqual(v4DefaultPreferences());
    }
  });
});
