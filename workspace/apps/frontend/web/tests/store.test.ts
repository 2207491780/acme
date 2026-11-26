import { beforeEach, describe, expect, it, vi } from 'vitest';
import { STORAGE_KEY } from '@acme/core';
import type { StorageLike, ThemePreferences } from '@acme/core';
import { createAppStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/dom';

let storage: StorageLike;
let store: ReturnType<typeof createAppStore>;

beforeEach(() => {
  storage = memoryStorage();
  store = createAppStore(storage);
});

const renameFirstApp = (target: ReturnType<typeof createAppStore>, name: string) =>
  target.getState().dispatch({
    type: 'UPDATE_APP',
    id: target.getState().state.apps[0].id,
    patch: { name },
  });

describe('createAppStore（发布域删除后的基座回归）', () => {
  it('载入种子基座且不含发布域字段', () => {
    const { state } = store.getState();
    expect(state.apps).toHaveLength(3);
    expect(state.apps.map((app) => app.platform).sort()).toEqual(['desktop', 'mobile', 'web']);
    expect(state.settings).toBeTruthy();
    for (const key of ['releases', 'order', 'savedViews', 'activity']) {
      expect(key in state, `基座状态不应含发布域字段 ${key}`).toBe(false);
    }
  });

  it('should_reject_invalid_import_without_overwriting_storage', () => {
    const persisted = storage.getItem(STORAGE_KEY);
    const before = store.getState().state;
    // 非法业务态：app.health 越界 → fail-closed，原状态与存储都不被覆盖
    const badData = {
      ...before,
      apps: [{ ...before.apps[0], health: 'perfect' }, ...before.apps.slice(1)],
    };
    expect(store.getState().dispatch({ type: 'IMPORT', data: badData })).toBe(false);
    expect(store.getState().state).toBe(before);
    expect(storage.getItem(STORAGE_KEY)).toBe(persisted);
  });

  it('should_expose_failed_persistence_and_keep_exportable_state', () => {
    let writable = false;
    const failing = createAppStore({
      getItem: (key) => storage.getItem(key),
      setItem: (key, value) => {
        if (!writable) throw new Error('QuotaExceededError');
        storage.setItem(key, value);
      },
    });
    expect(renameFirstApp(failing, '改名后')).toBe(true);
    expect(failing.getState().storageError).toBe(true);
    expect(failing.getState().state.apps[0].name).toBe('改名后');

    writable = true;
    const prefsOf = (): ThemePreferences =>
      (failing.getState().state.settings as { preferences: ThemePreferences }).preferences;
    failing.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...prefsOf(), mode: 'light' } },
    });
    expect(failing.getState().storageError).toBe(false);
    expect(JSON.parse(storage.getItem(STORAGE_KEY)!).apps[0].name).toBe('改名后');
  });

  it('should_pause_stale_user_action_instead_of_replaying_it', () => {
    const other = createAppStore(storage);
    expect(renameFirstApp(store, '本窗口改名')).toBe(true);
    const latest = storage.getItem(STORAGE_KEY);

    expect(renameFirstApp(other, '旧快照改名')).toBe(false);
    expect(other.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe(latest);
    expect(JSON.parse(latest!).apps[0].name).toBe('本窗口改名');
  });

  it('should_preserve_unsaved_state_when_another_window_changes_storage', () => {
    const failing = createAppStore({
      getItem: (key) => storage.getItem(key),
      setItem: () => { throw new Error('QuotaExceededError'); },
    });
    expect(renameFirstApp(failing, '未保存改名')).toBe(true);
    const unsaved = failing.getState().state;
    renameFirstApp(store, '另一窗口改名');
    const latest = storage.getItem(STORAGE_KEY);

    expect(renameFirstApp(failing, '旧快照改名')).toBe(false);
    expect(failing.getState().state).toBe(unsaved);
    expect(failing.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe(latest);
  });

  it('should_not_replace_state_with_invalid_external_data', () => {
    const before = store.getState().state;
    storage.setItem(STORAGE_KEY, '{broken');
    const prefsOf = (): ThemePreferences =>
      (before.settings as { preferences: ThemePreferences }).preferences;
    expect(store.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...prefsOf(), mode: 'light' } },
    })).toBe(false);
    expect(store.getState().state).toBe(before);
    expect(store.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe('{broken');
  });

  it('should_start_in_memory_when_localStorage_access_is_denied', async () => {
    vi.resetModules();
    vi.stubGlobal('window', Object.defineProperty({}, 'localStorage', {
      get() { throw new Error('SecurityError'); },
    }));
    try {
      const { useStore } = await import('../src/store/useStore');
      expect(useStore.getState().storageError).toBe(true);
      expect(useStore.getState().dispatch({
        type: 'UPDATE_APP',
        id: useStore.getState().state.apps[0].id,
        patch: { name: '离线改名' },
      })).toBe(true);
      expect(useStore.getState().state.apps[0].name).toBe('离线改名');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('resetData 恢复种子基座', () => {
    expect(renameFirstApp(store, '改名后')).toBe(true);
    store.getState().resetData();
    expect(store.getState().state.apps).toHaveLength(3);
    expect(store.getState().state.apps[0].name).not.toBe('改名后');
  });
});
