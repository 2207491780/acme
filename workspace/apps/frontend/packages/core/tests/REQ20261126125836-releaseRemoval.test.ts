/**
 * REQ-20261126-125836「删除多端发布演示功能」core 侧契约用例。
 *
 * 表面 = 契约 §验收标准 AC-1/AC-2/AC-3/AC-6/AC-8 + 确认记录③④：
 *   - 发布单域（类型/选择器/Action/种子）不再存在，apps/settings 基座保留；
 *   - 旧 blob 的 releases/order/savedViews/activity 与 topbarWidgets.release 接受但忽略，
 *     不升 schemaVersion、不做 v5 丢弃；非法输入仍按既有降级口径处理；
 *   - 共享 i18n 标签迁入 common 域，releases 域删除（zh-CN/en-US 双侧）。
 * 装配：core vitest 默认 node 环境；沿用 reducer/preferences 范本的纯函数调用口径。
 */
import { describe, expect, it } from 'vitest';
import * as core from '../src';
import { enUS } from '../src/i18n/resources/en-US';
import { zhCN } from '../src/i18n/resources/zh-CN';
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  STORAGE_KEY,
  i18n,
  migrateSettings,
  parseState,
  reduce,
  seedState,
  validateThemePreferences,
} from '../src';
import type { StorageLike } from '../src';

const now = Date.parse('2026-11-26T12:00:00Z');

const memoryStorage = (): StorageLike => {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) ?? null : null),
    setItem: (key, value) => void map.set(key, String(value)),
    removeItem: (key) => void map.delete(key),
  };
};

/** AC-1：类型只能在编译面验证，运行时以「发布域导出清零 + 基座导出在场」锁定。 */
describe('REQ20261126125836 core 发布域删除（AC-1）', () => {
  it('should_expose_no_release_domain_when_core_types_inspected', () => {
    const removedExports = [
      'validateReleaseInput', 'filterReleases', 'sortReleases', 'metrics',
      'isRelease', 'isArtifact', 'isTimelineEntry', 'isQuery', 'isSavedView',
      'isActivity', 'statusLabel', 'isVersion', 'ENVS', 'STATUSES',
      'ACTIVE_STATUSES', 'SORTS', 'NEXT_STATUS', 'STATUS_ACTION',
    ];
    for (const name of removedExports) {
      expect(name in core, `发布域导出 ${name} 应已删除`).toBe(false);
    }
    // 三端基座与主题偏好引擎保留
    for (const name of ['seedState', 'reduce', 'loadState', 'parseState', 'saveState',
      'validateThemePreferences', 'migrateSettings', 'DEFAULT_PREFERENCES']) {
      expect(name in core, `基座导出 ${name} 应保留`).toBe(true);
    }
    const state = seedState(now);
    expect(state.apps.map((app) => app.platform).sort()).toEqual(['desktop', 'mobile', 'web']);
    expect(state.settings).toBeTruthy();
  });
});

describe('REQ20261126125836 种子只含基座（AC-2）', () => {
  it('should_seed_only_foundation_when_release_domain_removed', () => {
    const state = seedState(now);
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in state, `种子不应含发布域字段 ${key}`).toBe(false);
    }
    expect(Object.keys(state).sort()).toEqual(['apps', 'settings', 'version'].sort());
    expect(state.apps).toHaveLength(3);
    expect(state.settings).toMatchObject({
      schemaVersion: PREFERENCES_VERSION,
      preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' }, // 种子偏好 mode 固定 dark，与出厂默认 light 不同
    });
    expect(JSON.stringify(state)).not.toContain('新建发布单');
  });
});

describe('REQ20261126125836 非发布回归保留（AC-10）', () => {
  it('should_keep_non_release_regression_when_release_tests_removed', () => {
    const state = seedState(now);
    const renamed = reduce(state, {
      type: 'UPDATE_APP',
      id: state.apps[0].id,
      patch: { name: '基座应用改名' },
    }, now);
    expect(renamed.apps[0].name).toBe('基座应用改名');
    expect(renamed.apps).toHaveLength(3);

    const themed = reduce(state, {
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'light' } },
    }, now);
    expect((themed.settings as { preferences: { mode: string } }).preferences.mode).toBe('light');
    expect((themed.settings as { schemaVersion: number }).schemaVersion).toBe(PREFERENCES_VERSION);
  });
});

describe('REQ20261126125836 旧数据兼容（AC-3）', () => {
  it('should_ignore_legacy_release_fields_when_loading_old_state', () => {
    const base = seedState(now);
    // 旧持久化 blob：发布单域四个字段仍在，over 里放垃圾值验证「完全忽略」而非逐字段校验。
    const legacy = {
      ...base,
      releases: [null, { id: 'REL-1', version: 123 }],
      order: ['REL-1', 42],
      savedViews: [{ id: 1, name: null }],
      activity: [{ id: '', at: 'invalid', text: null }],
    };
    const parsed = parseState(legacy, now);
    expect(parsed).not.toBeNull();
    expect(parsed!.apps).toEqual(base.apps);
    expect(parsed!.settings).toEqual(base.settings);
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in (parsed as object), `解析产物不应保留旧字段 ${key}`).toBe(false);
    }

    const storage = memoryStorage();
    storage.setItem(STORAGE_KEY, JSON.stringify(legacy));
    const loaded = core.loadState(storage, now);
    expect(loaded).toEqual(parsed);
    expect(storage.getItem(STORAGE_KEY)).toBe(JSON.stringify(legacy)); // 不覆盖旧数据
  });
});

describe('REQ20261126125836 legacy release widget（AC-6）', () => {
  it('should_ignore_legacy_release_widget_when_preferences_loaded', () => {
    const legacyWidgets = { ...DEFAULT_PREFERENCES.topbarWidgets, release: true };
    const validated = validateThemePreferences({
      ...DEFAULT_PREFERENCES,
      topbarWidgets: legacyWidgets,
    });
    expect(PREFERENCES_VERSION).toBe(4); // 不升版本、不做 v5 迁移
    expect('release' in validated.topbarWidgets).toBe(false);
    expect(validated.topbarWidgets).toEqual(DEFAULT_PREFERENCES.topbarWidgets);
    expect(validated.mode).toBe(DEFAULT_PREFERENCES.mode);

    const migrated = migrateSettings({
      schemaVersion: 3,
      preferences: { ...DEFAULT_PREFERENCES, topbarWidgets: { ...legacyWidgets, search: false } },
    });
    expect(migrated.settings.schemaVersion).toBe(PREFERENCES_VERSION);
    expect('release' in migrated.settings.preferences.topbarWidgets).toBe(false);
    expect(migrated.settings.preferences.topbarWidgets.search).toBe(false);
    expect(migrated.settings.preferences.mode).toBe(DEFAULT_PREFERENCES.mode);
  });
});

describe('REQ20261126125836 共享 i18n 标签（AC-8）', () => {
  const SHARED_KEYS = ['status', 'platform', 'platformFull', 'env', 'health'] as const;
  const bundle = (locale: 'zh-CN' | 'en-US'): Record<string, unknown> =>
    (locale === 'zh-CN' ? zhCN : enUS) as unknown as Record<string, unknown>;

  it('should_resolve_shared_labels_when_release_domain_removed', async () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      const resources = bundle(locale);
      expect('releases' in resources, `${locale} 不应再有 releases i18n 域`).toBe(false);
      const common = resources['common'] as Record<string, unknown>;
      expect(common).toBeTruthy();
      for (const key of SHARED_KEYS) {
        expect(common[key], `${locale} common.${key} 应存在`).toBeTruthy();
      }

      await i18n.changeLanguage(locale);
      for (const key of SHARED_KEYS) {
        const resolved = i18n.t(`common.${key}.${key === 'platformFull' ? 'desktop' : key === 'status' ? 'draft' : key === 'env' ? 'prod' : key === 'health' ? 'healthy' : 'web'}`);
        expect(resolved, `${locale} common.${key} 标签应解析为文案`).not.toBe(`common.${key}`);
      }
    }
    await i18n.changeLanguage('zh-CN');
  });
});
