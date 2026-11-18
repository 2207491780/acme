import { beforeEach, describe, expect, it, vi } from 'vitest';
import { STORAGE_KEY } from '@acme/core';
import type { ReleaseInput, StorageLike } from '@acme/core';
import { createAppStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/dom';

const validInput: ReleaseInput = {
  title: '新增灰度发布',
  appId: 'web',
  version: '1.5.0',
  env: 'test',
  owner: '林晚',
  priority: 1,
  notes: '',
};

let storage: StorageLike;
let store: ReturnType<typeof createAppStore>;

beforeEach(() => {
  storage = memoryStorage();
  store = createAppStore(storage);
});

describe('createAppStore', () => {
  it('载入种子数据', () => {
    const { state } = store.getState();
    expect(state.releases).toHaveLength(14);
    expect(state.apps).toHaveLength(3);
    expect(state.savedViews).toHaveLength(2);
  });

  it('dispatch 创建发布单并持久化', () => {
    const ok = store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    expect(ok).toBe(true);
    expect(store.getState().state.releases).toHaveLength(15);
    expect(store.getState().state.releases[0].title).toBe('新增灰度发布');
    expect(storage.getItem(STORAGE_KEY)).not.toBeNull();
  });

  it('非法输入被拒绝且不改变状态', () => {
    const before = store.getState().state;
    const ok = store.getState().dispatch({ type: 'CREATE_RELEASE', payload: { ...validInput, version: 'bad' } });
    expect(ok).toBe(false);
    expect(store.getState().state).toBe(before);
  });

  it('setQuery 过滤可见发布单', () => {
    store.getState().setQuery({ platform: 'mobile' });
    const visible = store.getState().visibleReleases();
    expect(visible.length).toBeGreaterThan(0);
    expect(visible.every((r) => r.platform === 'mobile')).toBe(true);
  });

  it('deleteReleases 返回可撤销项并移除', () => {
    const id = store.getState().state.releases[0].id;
    const items = store.getState().deleteReleases([id]);
    expect(items).toHaveLength(1);
    expect(store.getState().state.releases.some((r) => r.id === id)).toBe(false);
    store.getState().dispatch({ type: 'RESTORE_RELEASES', items });
    expect(store.getState().state.releases.some((r) => r.id === id)).toBe(true);
  });

  it('advance 推进状态', () => {
    const draft = store.getState().state.releases.find((r) => r.status === 'draft')!;
    const ok = store.getState().advance(draft.id);
    expect(ok).toBe(true);
    expect(store.getState().state.releases.find((r) => r.id === draft.id)!.status).toBe('building');
  });

  it('bulkAdvance 推进所选并计数', () => {
    const ids = store.getState().state.releases.filter((r) => r.status === 'draft').map((r) => r.id);
    store.getState().setSelection(ids);
    const count = store.getState().bulkAdvance();
    expect(count).toBe(ids.length);
  });

  it('applyView 应用保存视图', () => {
    store.getState().applyView('view-prod');
    expect(store.getState().query.env).toBe('prod');
    expect(store.getState().query.sort).toBe('updated');
  });

  it('toggleSelect 与 clearSelection', () => {
    const id = store.getState().state.releases[0].id;
    store.getState().toggleSelect(id, true);
    expect(store.getState().selection).toContain(id);
    store.getState().clearSelection();
    expect(store.getState().selection).toHaveLength(0);
  });

  it('should_reject_invalid_import_without_overwriting_storage', () => {
    store.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'light' } });
    const before = store.getState().state;
    const persisted = storage.getItem(STORAGE_KEY);
    const data = { ...before, settings: { ...before.settings, density: 'dense' } };
    expect(store.getState().dispatch({ type: 'IMPORT', data })).toBe(false);
    expect(store.getState().state).toBe(before);
    expect(storage.getItem(STORAGE_KEY)).toBe(persisted);
  });

  it('should_refresh_stale_snapshot_without_overwriting_new_records_when_ticking', () => {
    const other = createAppStore(storage);
    store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    const created = store.getState().state.releases[0].id;
    const latest = storage.getItem(STORAGE_KEY);
    expect(other.getState().dispatch({ type: 'TICK' })).toBe(false);
    expect(storage.getItem(STORAGE_KEY)).toBe(latest);
    expect(other.getState().state.releases.some((r) => r.id === created)).toBe(true);
    other.getState().dispatch({ type: 'TICK' });
    expect(JSON.parse(storage.getItem(STORAGE_KEY)!).releases.some((r: { id: string }) => r.id === created)).toBe(true);
  });

  it('should_pause_stale_user_action_instead_of_replaying_it', () => {
    const other = createAppStore(storage);
    store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    const latest = storage.getItem(STORAGE_KEY);
    const target = other.getState().state.releases[0].id;
    expect(other.getState().deleteReleases([target])).toEqual([]);
    expect(other.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe(latest);
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
    expect(failing.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput })).toBe(true);
    expect(failing.getState().storageError).toBe(true);
    expect(failing.getState().state.releases[0].title).toBe(validInput.title);
    writable = true;
    failing.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'light' } });
    expect(failing.getState().storageError).toBe(false);
    expect(JSON.parse(storage.getItem(STORAGE_KEY)!).releases[0].title).toBe(validInput.title);
  });

  it('should_preserve_unsaved_state_when_another_window_changes_storage', () => {
    const failing = createAppStore({
      getItem: (key) => storage.getItem(key),
      setItem: () => { throw new Error('QuotaExceededError'); },
    });
    failing.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    const unsaved = failing.getState().state;
    store.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'light' } });
    const latest = storage.getItem(STORAGE_KEY);
    expect(failing.getState().dispatch({ type: 'TICK' })).toBe(false);
    expect(failing.getState().state).toBe(unsaved);
    expect(failing.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe(latest);
  });

  it('should_not_replace_state_with_invalid_external_data', () => {
    const before = store.getState().state;
    storage.setItem(STORAGE_KEY, '{broken');
    expect(store.getState().dispatch({ type: 'TICK' })).toBe(false);
    expect(store.getState().state).toBe(before);
    expect(store.getState().storageConflict).toBe(true);
    expect(storage.getItem(STORAGE_KEY)).toBe('{broken');
  });

  it.each(['filter', 'search', 'view', 'reset'] as const)('should_clear_selection_when_query_changes_via_%s', (change) => {
    const target = store.getState().state.releases.find((r) => r.status === 'approved')!;
    store.getState().setSelection([target.id]);
    if (change === 'filter') store.getState().setQuery({ env: 'dev' });
    if (change === 'search') store.getState().setQuery({ search: 'not-found' });
    if (change === 'view') store.getState().applyView('view-prod');
    if (change === 'reset') store.getState().resetQuery();
    expect(store.getState().selection).toEqual([]);
    expect(store.getState().bulkAdvance()).toBe(0);
    expect(store.getState().state.releases.find((r) => r.id === target.id)?.status).toBe('approved');
  });

  it('should_clear_transient_references_when_import_replaces_data', () => {
    const data = store.getState().state;
    store.getState().setQuery({ search: 'hidden' });
    store.getState().setSelection([data.releases[0].id]);
    store.getState().openDrawer(data.releases[0].id);
    expect(store.getState().dispatch({ type: 'IMPORT', data })).toBe(true);
    expect(store.getState().selection).toEqual([]);
    expect(store.getState().drawerId).toBeNull();
    expect(store.getState().query.search).toBe('');
  });

  it('should_clear_stale_references_but_keep_valid_ones_when_conflict_reloads_replaced_data', () => {
    const [target, kept] = store.getState().state.releases;
    store.getState().openDrawer(target.id);
    store.getState().openModal({ type: 'release', id: kept.id });
    const other = createAppStore(storage);
    other.getState().deleteReleases([target.id]);
    // 本窗口感知到存储变化后重载：失效引用清空，仍存在的引用保留。
    expect(store.getState().dispatch({ type: 'TICK' })).toBe(false);
    expect(store.getState().drawerId).toBeNull();
    expect(store.getState().modal).toMatchObject({ type: 'release', id: kept.id });
    expect(store.getState().state.releases.some((r) => r.id === target.id)).toBe(false);
  });

  it('should_start_in_memory_when_localStorage_access_is_denied', async () => {
    vi.resetModules();
    vi.stubGlobal('window', Object.defineProperty({}, 'localStorage', {
      get() { throw new Error('SecurityError'); },
    }));
    try {
      const { useStore } = await import('../src/store/useStore');
      expect(useStore.getState().storageError).toBe(true);
      expect(useStore.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput })).toBe(true);
      expect(useStore.getState().state.releases[0].title).toBe(validInput.title);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('resetData 恢复种子', () => {
    store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    store.getState().resetData();
    expect(store.getState().state.releases).toHaveLength(14);
    expect(store.getState().selection).toHaveLength(0);
  });
});