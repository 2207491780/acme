import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_PREFERENCES, STORAGE_KEY } from '@acme/core';
import type { StorageLike } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/storage';

let storage: StorageLike;
let store: ReturnType<typeof createMobileStore>;

beforeEach(() => {
  storage = memoryStorage();
  store = createMobileStore(storage);
});

const prefsOf = (target: ReturnType<typeof createMobileStore>): { mode: string; accent: string } =>
  (target.getState().state.settings as unknown as { preferences: { mode: string; accent: string } }).preferences;

describe('createMobileStore（与 Web 同构，共享 core 基座）', () => {
  it('should_load_foundation_seed_when_release_domain_removed', () => {
    const { state } = store.getState();
    expect(state.apps).toHaveLength(3);
    expect(state.apps.map((app) => app.platform).sort()).toEqual(['desktop', 'mobile', 'web']);
    expect(state.settings).toBeTruthy();
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in state, `基座状态不应含发布域字段 ${key}`).toBe(false);
    }
  });

  it('UPDATE_SETTINGS 更新强调色并持久化', () => {
    const ok = store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, accent: 'magenta' } },
    });
    expect(ok).toBe(true);
    expect(prefsOf(store).accent).toBe('magenta');
    expect(storage.getItem(STORAGE_KEY)).not.toBeNull();
    expect(JSON.parse(storage.getItem(STORAGE_KEY)!).settings.preferences.accent).toBe('magenta');
    expect('accent' in (store.getState().state.settings as object)).toBe(false); // 投影字段零残留
  });

  it('resetData 恢复种子基座', () => {
    store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'light' } },
    });
    expect(prefsOf(store).mode).toBe('light');
    store.getState().resetData();
    expect(prefsOf(store).mode).toBe(prefsOf(createMobileStore(memoryStorage())).mode);
    expect(store.getState().state.apps).toHaveLength(3);
  });
});
