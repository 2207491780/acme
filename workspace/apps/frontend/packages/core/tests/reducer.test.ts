import { describe, it, expect, vi } from 'vitest';
import {
  seedState,
  reduce,
  loadState,
  saveState,
  parseState,
  DEFAULT_PREFERENCES,
  PREFERENCES_VERSION,
  STORAGE_KEY,
} from '../src';
import type { AppState, StorageLike, ThemePreferences } from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');

const memoryStorage = (): StorageLike => {
  const map = new Map<string, string>();
  return {
    getItem: (k) => (map.has(k) ? map.get(k)! : null),
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
  };
};

describe('reducer', () => {
  it('should_seed_only_three_foundation_apps_when_release_domain_removed', () => {
    const state = seedState(now);
    expect(state.apps).toHaveLength(3);
    expect(state.apps.map((app) => app.platform).sort()).toEqual(['desktop', 'mobile', 'web']);
    expect(state.settings).toBeTruthy();
  });

  it('should_persist_and_reload_state_when_storage_round_trips', () => {
    const storage = memoryStorage();
    const state = seedState(now);
    expect(saveState(storage, state)).toBe(true);
    const loaded = loadState(storage, now);
    expect(loaded).toEqual(state);
    expect(storage.getItem(STORAGE_KEY)).toBeTruthy();
  });

  it('should_reject_update_app_when_patch_contains_unknown_field', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { id: 'hacked' } }, now);
    expect(next).toBe(state);
    expect(next.apps[0].id).toBe(target.id);
  });

  it('should_reject_update_app_when_health_not_in_enum', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { health: 'perfect' } }, now);
    expect(next).toBe(state);
    expect(next.apps[0].health).toBe(target.health);
  });

  it('should_apply_update_app_when_patch_valid', () => {
    const state = seedState(now);
    const target = state.apps[0];
    const next = reduce(state, { type: 'UPDATE_APP', id: target.id, patch: { name: '改名后', owner: '新负责人' } }, now);
    expect(next).not.toBe(state);
    expect(next.apps[0].name).toBe('改名后');
    expect(next.apps[0].owner).toBe('新负责人');
    expect(next.apps[0].health).toBe(target.health);
  });

  it('should_apply_settings_patch_via_preferences_or_legacy_fields_when_update_settings_dispatched', () => {
    // UPDATE_SETTINGS 的 patch 支持 preferences 内嵌对象或 legacy 扁平字段
    // （theme/dark→mode、accent/density/motion 同名映射），合并后经 validateThemePreferences 归一；
    // 投影字段已删除（THEME-3 ⑧），schemaVersion 不接受补丁改写。
    const base = seedState(now);
    const settingsOf = (state: ReturnType<typeof seedState>) =>
      state.settings as unknown as {
        schemaVersion: number;
        preferences: ThemePreferences;
      };

    const withPrefs = reduce(base, {
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'emerald', customAccent: null, density: 'compact', motionEnabled: false } },
    }, now);
    const prefs = settingsOf(withPrefs);
    expect(withPrefs).not.toBe(base);
    expect(prefs.schemaVersion).toBe(PREFERENCES_VERSION);
    expect(prefs.preferences).toEqual({
      ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'emerald', customAccent: null, density: 'compact', motionEnabled: false,
    });
    for (const key of ['theme', 'accent', 'density', 'motion'] as const) {
      expect(key in prefs, `投影字段 ${key} 删除后不得残留（THEME-3 ⑧）`).toBe(false);
    }

    const lightState = reduce(withPrefs, {
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...settingsOf(withPrefs).preferences, mode: 'light' } },
    }, now);
    expect(settingsOf(lightState).preferences.mode).toBe('light');
    const withLegacy = reduce(lightState, { type: 'UPDATE_SETTINGS', patch: { theme: 'dark' } }, now);
    expect(settingsOf(withLegacy).preferences.mode).toBe('dark');

    const withCustom = reduce(base, {
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'cyan', customAccent: '#b08968' } },
    }, now);
    expect(settingsOf(withCustom).preferences.customAccent).toBe('#b08968');
    const withPreset = reduce(withCustom, { type: 'UPDATE_SETTINGS', patch: { accent: 'emerald' } }, now);
    expect(settingsOf(withPreset).preferences.accent).toBe('emerald');
    expect(settingsOf(withPreset).preferences.customAccent).toBeNull();

    const withVersionHack = reduce(base, { type: 'UPDATE_SETTINGS', patch: { schemaVersion: 99 } }, now);
    expect(settingsOf(withVersionHack).schemaVersion).toBe(PREFERENCES_VERSION);
  });

  it('should_ignore_legacy_release_fields_when_importing_old_state', () => {
    const base = seedState(now);
    const legacy = {
      ...base,
      releases: [{ id: 'REL-1', title: '旧发布单', version: 123 }],
      order: ['REL-1'],
      savedViews: [{ id: 1, name: null }],
      activity: [{ id: '', at: 'invalid', text: null }],
    };
    const imported = reduce(base, { type: 'IMPORT', data: legacy }, now);
    expect(imported.apps).toEqual(base.apps);
    expect(imported.settings).toEqual(base.settings);
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in imported, `IMPORT 产物不应保留旧字段 ${key}`).toBe(false);
    }
  });
});

describe('state boundary', () => {
  type InvalidCase = [string, (state: AppState) => unknown];
  const appFields: [string, unknown][] = [
    ['id', ''], ['name', null], ['platform', 'native'], ['path', 123],
    ['version', 1], ['owner', null], ['health', 'unknown'], ['description', {}],
  ];
  const settingsFields: [string, unknown][] = [
    ['theme', 'system'], ['density', 'tiny'], ['accent', 'red'], ['motion', 'false'],
    ['theme', null], ['density', undefined],
  ];
  const invalidCases: InvalidCase[] = [
    ['null root', () => null],
    ['array root', () => []],
    ['missing apps', (state) => ({ settings: state.settings })],
    ['null app', (state) => ({ ...state, apps: [null] })],
    ['incomplete app', (state) => ({ ...state, apps: [{ id: 'web' }] })],
    ['duplicate apps', (state) => ({ ...state, apps: [...state.apps, state.apps[0]] })],
    ['array settings', (state) => ({ ...state, settings: [] })],
    ['infinite version', (state) => ({ ...state, version: Infinity })],
    ['NaN version', (state) => ({ ...state, version: NaN })],
    ...['settings', 'version'].flatMap((field) =>
      [null, undefined, 'invalid'].map<InvalidCase>((value) => [
        `${field} ${String(value)}`, (state) => ({ ...state, [field]: value }),
      ])),
    ...appFields.map<InvalidCase>(([field, value]) => [
      `app ${field}`, (state) => ({
        ...state, apps: [{ ...state.apps[0], [field]: value }, ...state.apps.slice(1)],
      }),
    ]),
    ...settingsFields.map<InvalidCase>(([field, value]) => [
      `settings ${field} ${String(value)}`, (state) => ({ ...state, settings: { [field]: value } }),
    ]),
  ];

  it.each(invalidCases)('should_reject_state_when_invalid_%s', (_, makeData) => {
    const state = seedState(now);
    const snapshot = structuredClone(state);
    const data = makeData(state);
    expect(parseState(data, now)).toBeNull();
    expect(reduce(state, { type: 'IMPORT', data }, now)).toBe(state);
    expect(state).toEqual(snapshot);
  });

  it('should_fill_defaults_when_valid_partial_state_is_provided', () => {
    const base = seedState(now);
    const data = {
      apps: base.apps,
      settings: { density: 'compact', motion: false },
    };
    const seedPrefs = (base.settings as { preferences: { mode: 'light' | 'dark' | 'system'; accent: string; customAccent: string | null; density: 'comfortable' | 'compact'; motionEnabled: boolean } }).preferences;
    const expectedSettings = {
      schemaVersion: PREFERENCES_VERSION,
      preferences: { ...seedPrefs, density: 'compact', motionEnabled: false },
    };
    const expected = { ...base, settings: expectedSettings };
    expect(parseState(data, now)).toEqual(expected);
    const storage = memoryStorage();
    storage.setItem(STORAGE_KEY, JSON.stringify(data));
    expect(loadState(storage, now)).toEqual(expected);
    const imported = reduce(base, { type: 'IMPORT', data }, now);
    expect(imported).toEqual(expected);
  });

  it('should_preserve_explicit_empty_arrays_without_seed_references_when_parsing', () => {
    const data = { apps: [], settings: {} };
    const base = seedState(now);
    const expected = { ...data, settings: base.settings, version: base.version };
    expect(parseState(data, now)).toEqual(expected);
    const imported = reduce(base, { type: 'IMPORT', data }, now);
    expect(imported.apps).toEqual([]);
  });

  it('should_round_trip_exported_state_without_mutating_input_when_importing', () => {
    const base = seedState(now);
    const state = reduce(base, {
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' } },
    }, now);
    const data = JSON.parse(JSON.stringify(state));
    expect(parseState(data, now)).toEqual(state);
    const imported = reduce(seedState(now), { type: 'IMPORT', data }, now);
    expect(imported).toEqual(state);
    expect(data).toEqual(state);
    expect(parseState(JSON.parse(JSON.stringify(imported)), now)).toEqual(imported);
  });

  it.each([
    '{bad json',
    'null',
    JSON.stringify({ apps: [], settings: { density: 'tiny' } }),
    JSON.stringify({ apps: [null] }),
    JSON.stringify({ apps: 'not-an-array' }),
    JSON.stringify({ apps: [], settings: [] }),
  ])('should_fall_back_without_overwriting_storage_when_persisted_data_is_invalid_%s', (raw) => {
    const storage = memoryStorage();
    storage.setItem(STORAGE_KEY, raw);
    const write = vi.spyOn(storage, 'setItem');
    expect(loadState(storage, now)).toEqual(seedState(now));
    expect(storage.getItem(STORAGE_KEY)).toBe(raw);
    expect(write).not.toHaveBeenCalled();
  });

  it('should_fall_back_without_writing_when_storage_read_throws', () => {
    const storage: StorageLike = {
      getItem: () => { throw new Error('read denied'); },
      setItem: vi.fn(),
    };
    expect(loadState(storage, now)).toEqual(seedState(now));
    expect(storage.setItem).not.toHaveBeenCalled();
  });

  it('should_return_false_and_preserve_memory_when_storage_write_throws', () => {
    const state = seedState(now);
    const snapshot = structuredClone(state);
    const storage: StorageLike = {
      getItem: () => null,
      setItem: () => { throw new Error('quota exceeded'); },
    };
    expect(saveState(storage, state)).toBe(false);
    expect(state).toEqual(snapshot);
  });
});
