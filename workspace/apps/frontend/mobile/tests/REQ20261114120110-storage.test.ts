import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_PREFERENCES, STORAGE_KEY, parseState, seedState } from '@acme/core';
import type { AppState } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { initMobileStorage, mobileStorage } from '../src/utils/storage';

/**
 * REQ-20261114-120110 AC-3（AsyncStorage 适配器接入层，REQ-20261126-125836 适配）：
 * 冷启动 hydrate 走 core parse-state 校验管道；发布单域删除后合法面为 apps/settings，
 * 旧 blob 的发布域字段接受但忽略；非法数据回落种子态、不覆盖内存有效状态。
 * mock 范本：mobile/tests/auth.test.ts（vi.hoisted 内存桩 + vi.mock 原生模块）。
 */
const asyncMem = vi.hoisted(() => {
  const store = new Map<string, string>();
  const api = {
    getItem: vi.fn(async (key: string) => store.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn(async (key: string) => {
      store.delete(key);
    }),
  };
  return { store, api };
});
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: asyncMem.api,
  AsyncStorage: asyncMem.api,
}));

const now = Date.now();

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

const settingsMode = (state: AppState): string =>
  (state.settings as unknown as { preferences: { mode: string } }).preferences.mode;

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  asyncMem.store.clear();
});

describe('REQ20261114120110 AsyncStorage 持久化适配器（AC-3）', () => {
  it('should_restore_business_state_on_cold_start_when_async_storage_valid', async () => {
    asyncMem.store.set(STORAGE_KEY, JSON.stringify(validPersistedState()));

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    expect(store.getState().state.apps).toEqual(seedState(now).apps);
    expect(settingsMode(store.getState().state)).toBe('light');
    expect(settingsMode(seedState(now))).not.toBe('light');
  });

  it('should_ignore_legacy_release_fields_when_async_storage_has_old_blob', async () => {
    const legacy = {
      ...validPersistedState(),
      releases: [null],
      order: ['REL-1'],
      savedViews: [null],
      activity: [null],
    };
    asyncMem.store.set(STORAGE_KEY, JSON.stringify(legacy));

    await initMobileStorage();
    const restored = createMobileStore(mobileStorage).getState().state;
    expect(settingsMode(restored)).toBe('light');
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in restored, `旧字段 ${key} 应被接受但忽略`).toBe(false);
    }
  });

  it('should_fallback_to_seed_when_async_storage_empty', async () => {
    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    expect(store.getState().state.apps).toHaveLength(3);
    expect(settingsMode(store.getState().state)).toBe(settingsMode(seedState(now)));
  });

  it('should_fallback_to_seed_when_async_storage_json_broken', async () => {
    asyncMem.store.set(STORAGE_KEY, '{not-json');

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    expect(store.getState().state.apps).toHaveLength(3);
    expect(parseState(JSON.parse(JSON.stringify(store.getState().state)), now)).not.toBeNull();
  });

  it('should_fallback_to_seed_when_async_storage_field_type_invalid', async () => {
    const corrupted = validPersistedState() as unknown as { apps: Array<Record<string, unknown>> };
    corrupted.apps[0]!.health = 'unknown';
    asyncMem.store.set(STORAGE_KEY, JSON.stringify(corrupted));

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    expect(store.getState().state.apps).toEqual(seedState(now).apps);
    expect(settingsMode(store.getState().state)).toBe(settingsMode(seedState(now)));
  });

  it('should_recover_persistence_after_invalid_data_when_user_dispatches', async () => {
    asyncMem.store.set(STORAGE_KEY, '{not-json');

    await initMobileStorage();
    const store = createMobileStore(mobileStorage);
    const ok = store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' } },
    });
    expect(ok).toBe(true);
    await flush();

    const persisted = asyncMem.store.get(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    expect(parseState(JSON.parse(persisted!), now)).not.toBeNull();
    expect(settingsMode(parseState(JSON.parse(persisted!), now)!)).toBe('dark');
  });

  it('should_persist_dispatched_state_to_async_storage_key', async () => {
    await initMobileStorage();
    const store = createMobileStore(mobileStorage);

    const ok = store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, accent: 'ruby' } },
    });
    expect(ok).toBe(true);
    await flush();

    const persisted = asyncMem.store.get(STORAGE_KEY);
    expect(persisted).not.toBeNull();
    const parsed: unknown = JSON.parse(persisted!);
    expect((parsed as { settings: { preferences: { accent: string } } }).settings.preferences.accent).toBe('ruby');
  });
});
