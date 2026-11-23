/**
 * REQ-20261121-200415（THEME-3）AC-6：偏好迁移无损 + 投影字段删除后编译面契约用例。
 *
 * 表面 = 契约确认记录⑧ + AC-6：
 *   parse-state legacy 扁平迁移（v1/v2 blob→v3）保留（旧数据无损窗口）；
 *   core types.ts 4 个 @deprecated 投影字段（theme/accent/density/motion）物理删除；
 *   旧持久化 blob（v1 扁平 / v2 / v3 投影形态）迁移后无损到新 shape 且投影字段不再存在。
 *
 * 迁移入口 = migrateSettings（契约钉死导出面；010725/043502 既有范本口径：
 *   返回 { settings; migrated }，settings = { schemaVersion; preferences }）。
 * 装配：node 环境纯函数单测。
 * 注意：010725 既有用例中「投影字段存在且一致」的三段断言属 THEME-3 删除前的历史口径，
 * 由实现侧随投影删除同步适配；本文件锁定删除后的新 shape。
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  migrateSettings,
  registerPreferenceExtension,
  type ThemePreferences,
} from '../src';

const PROJECTION_KEYS = ['theme', 'accent', 'density', 'motion'] as const;

/** 投影字段零残留断言（AC-6「投影字段不再存在」+确认记录⑧物理删除）。 */
const expectNoProjections = (settings: Record<string, unknown>): void => {
  for (const key of PROJECTION_KEYS) {
    expect(key in settings, `迁移后投影字段 ${key} 不得残留`).toBe(false);
  }
};

/** v3 全字段基线（可覆盖）。v4 适配（REQ-20261122-233427）：补 v4 默认字段（schema 升版后类型全集）。 */
const v3Preferences = (over: Partial<ThemePreferences> = {}): ThemePreferences => ({
  mode: 'dark',
  accent: 'violet',
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
    themeToggle: true, lock: true, fullscreen: true, settings: true, release: true,
  },
  ...over,
});

describe('REQ20261121200415 当前版本 blob 直通（AC-6 迁移窗口边界）', () => {
  it('should_keep_current_shape_blob_without_migration_when_version_current', () => {
    // 本用例先于本文件任何 registerPreferenceExtension 调用执行：
    // 未注册域时 extensions 键不得出现（契约⑤「仅注册后出现」在迁移面同口径验证）
    const current = { schemaVersion: PREFERENCES_VERSION, preferences: { ...DEFAULT_PREFERENCES } };
    const result = migrateSettings(current);

    expect(result.migrated).toBe(false);
    expect(result.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(result.settings.preferences).toEqual(current.preferences);
    expectNoProjections(result.settings as unknown as Record<string, unknown>);
  });
});

describe('REQ20261121200415 legacy blob 迁移无损（AC-6）', () => {
  it('should_migrate_v1_flat_blob_to_v3_shape_without_projection_fields', () => {
    // v1 扁平：无 schemaVersion，旧语义 dark/accent/density/motion 四字段（010725 范本 fixture 口径）
    const v1Blob = { dark: true, accent: 'emerald', density: 'compact', motion: false };
    const migrated = migrateSettings(v1Blob);

    expect(migrated.migrated).toBe(true);
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(migrated.settings.preferences).toEqual(
      v3Preferences({ mode: 'dark', accent: 'emerald', customAccent: null, motionEnabled: false }),
    );
    expectNoProjections(migrated.settings as unknown as Record<string, unknown>);
  });

  it('should_migrate_v2_blob_losslessly_and_strip_projections', () => {
    // v2 投影形态：schemaVersion=2 + THEME-1 五字段 preferences + 4 个派生投影字段（持久化 blob 全形）
    const v2Blob = {
      schemaVersion: 2,
      preferences: { mode: 'light', accent: 'cyan', customAccent: null, density: 'comfortable', motionEnabled: true },
      theme: 'light',
      accent: 'cyan',
      density: 'comfortable',
      motion: true,
    };
    const migrated = migrateSettings(v2Blob);

    expect(migrated.migrated).toBe(true);
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    // 无损：v2 preferences 逐字段保留 + v3 新字段补默认（043502 口径）
    expect(migrated.settings.preferences).toEqual(
      v3Preferences({ mode: 'light', accent: 'cyan', customAccent: null, density: 'comfortable', motionEnabled: true }),
    );
    expectNoProjections(migrated.settings as unknown as Record<string, unknown>);
  });

  it('should_strip_stale_projection_fields_when_v3_blob_carries_projections', () => {
    // v3 投影形态：已是当前版本但 blob 内仍带历史投影字段（THEME-1~2 持久化存量）
    const v3WithProjections = {
      schemaVersion: 3,
      preferences: v3Preferences(),
      theme: 'dark',
      accent: 'violet',
      density: 'compact',
      motion: false,
    };
    const migrated = migrateSettings(v3WithProjections);

    // 无损：preferences 原样保留；投影字段不再存在（AC-6 v3 投影形态输入面）
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(migrated.settings.preferences).toEqual(v3Preferences());
    expectNoProjections(migrated.settings as unknown as Record<string, unknown>);
  });

  it('should_preserve_extensions_payload_when_migrating_v3_blob', () => {
    // 迁移无损窗口覆盖 extensions（含未注册域）：blob 存储内容不得在迁移中丢失。
    // 注册表为文件级全局：本用例注册后，先前用例已注册域会以 defaults 物化，
    // 故 extensions 按域断言而非整体 toEqual（契约⑤物化口径）。
    registerPreferenceExtension('migrationProbe', {
      defaults: { on: false },
      parse: (raw: unknown) => raw,
    });
    const foreign = { anyShape: [1, 'x', { deep: true }] };
    const blob = {
      schemaVersion: 3,
      preferences: {
        ...v3Preferences(),
        extensions: { migrationProbe: { on: true }, foreignDomain: foreign },
      },
    };
    const migrated = migrateSettings(blob);
    const extensions = (migrated.settings.preferences as unknown as Record<string, unknown>)['extensions'] as Record<string, unknown>;

    expect(extensions['migrationProbe']).toEqual({ on: true });
    expect(extensions['foreignDomain']).toEqual(foreign);
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    // 核心偏好字段无损
    expect(migrated.settings.preferences).toMatchObject(v3Preferences());
  });

  it('should_return_defaults_without_projections_when_migrate_input_invalid', () => {
    // 非法输入不抛错回默认（010725 口径延续），且删除后无投影字段残留
    const garbageInputs: unknown[] = [null, undefined, 42, '{broken', []];
    for (const garbage of garbageInputs) {
      expect(() => migrateSettings(garbage)).not.toThrow();
      const result = migrateSettings(garbage).settings;
      expect(result.schemaVersion).toBe(PREFERENCES_VERSION);
      expect(result.preferences).toEqual({ ...DEFAULT_PREFERENCES });
      expectNoProjections(result as unknown as Record<string, unknown>);
    }
  });
});
