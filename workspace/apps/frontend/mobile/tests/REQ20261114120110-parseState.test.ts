import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFERENCES,
  STORAGE_KEY,
  parseState,
  seedState,
} from '@acme/core';
import type { AppState, StorageLike } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/storage';

/**
 * REQ-20261114-120110 AC-3（parse-state 校验管道层，REQ-20261126-125836 适配）：
 * AsyncStorage 落地后冷启动恢复仍走 core `loadState → parseState` 运行时校验管道。
 * 发布单域已删除：合法持久化面改为 apps/settings；旧 blob 的 releases/order/savedViews/
 * activity 字段接受但忽略，非法输入仍回落种子态、不覆盖有效状态。
 */
const now = Date.now();

/** 经 core reducer 产出的合法基座状态（settings 与种子可区分）。 */
function validPersistedState(): AppState {
  const base = seedState(now);
  return {
    ...base,
    settings: {
      ...base.settings,
      preferences: { ...DEFAULT_PREFERENCES, mode: 'light', accent: 'emerald' },
    },
  };
}

function storeOn(rawStorage: StorageLike) {
  return createMobileStore(rawStorage);
}

function seedRaw(storage: StorageLike, raw: string): void {
  storage.setItem(STORAGE_KEY, raw);
}

const settingsMode = (state: AppState): string =>
  (state.settings as unknown as { preferences: { mode: string } }).preferences.mode;

describe('REQ20261114120110 冷启动恢复 parse-state 校验管道（AC-3）', () => {
  it('should_restore_persisted_state_on_cold_start_when_persisted_data_valid', () => {
    const storage = memoryStorage();
    seedRaw(storage, JSON.stringify(validPersistedState()));

    const restored = storeOn(storage).getState().state;

    expect(restored.apps).toEqual(seedState(now).apps);
    expect(settingsMode(restored)).toBe('light');
    expect(settingsMode(seedState(now))).not.toBe('light'); // 恢复持久化状态而非种子
  });

  it('should_ignore_legacy_release_fields_when_loading_old_state', () => {
    const storage = memoryStorage();
    const legacy = {
      ...validPersistedState(),
      releases: [null, { id: 'REL-1', version: 123 }],
      order: ['REL-1', 42],
      savedViews: [{ id: 1, name: null }],
      activity: [{ id: '', at: 'invalid', text: null }],
    };
    seedRaw(storage, JSON.stringify(legacy));

    const restored = storeOn(storage).getState().state;
    expect(settingsMode(restored)).toBe('light');
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in restored, `旧字段 ${key} 应被接受但忽略`).toBe(false);
    }
    expect(parseState(legacy, now)).not.toBeNull();
  });

  it('should_fallback_to_seed_when_persisted_json_broken', () => {
    const storage = memoryStorage();
    seedRaw(storage, '{not-json');

    const store = storeOn(storage);

    expect(store.getState().state.apps).toHaveLength(3);
    expect(parseState(JSON.parse(JSON.stringify(store.getState().state)), now)).not.toBeNull();
  });

  it('should_fallback_to_seed_when_persisted_field_type_invalid', () => {
    const storage = memoryStorage();
    const corrupted = validPersistedState() as unknown as { apps: Array<Record<string, unknown>> };
    corrupted.apps[0]!.health = 'unknown';
    seedRaw(storage, JSON.stringify(corrupted));

    const store = storeOn(storage);

    expect(store.getState().state.apps).toEqual(seedState(now).apps);
    expect(settingsMode(store.getState().state)).toBe(settingsMode(seedState(now)));
  });

  it('should_restore_persisted_state_when_settings_projection_key_corrupted', () => {
    const storage = memoryStorage();
    const corrupted = validPersistedState() as unknown as { settings: Record<string, unknown> };
    corrupted.settings.theme = 'neon';
    seedRaw(storage, JSON.stringify(corrupted));

    const store = storeOn(storage);

    expect(settingsMode(store.getState().state)).toBe('light');
    const settings = store.getState().state.settings as unknown as { theme?: unknown };
    expect('theme' in settings, '投影字段 theme 删除后不得残留').toBe(false);
  });

  it('should_recover_persistence_after_invalid_data_when_user_dispatches', () => {
    const storage = memoryStorage();
    seedRaw(storage, '{not-json');

    const store = storeOn(storage);
    const ok = store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' } },
    });
    expect(ok).toBe(true);
    expect(settingsMode(store.getState().state)).toBe('dark');

    const persisted = storage.getItem(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    expect(parseState(JSON.parse(persisted!), now)).not.toBeNull();
    expect(settingsMode(parseState(JSON.parse(persisted!), now)!)).toBe('dark');
  });

  it('should_return_null_from_parse_state_when_app_platform_mismatches', () => {
    const corrupted = validPersistedState() as unknown as { apps: Array<Record<string, unknown>> };
    corrupted.apps[0]!.platform = 'native';
    expect(parseState(corrupted, now)).toBeNull();
  });
});
