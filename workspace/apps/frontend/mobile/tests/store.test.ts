import { beforeEach, describe, expect, it } from 'vitest';
import { STORAGE_KEY } from '@acme/core';
import type { ReleaseInput, StorageLike } from '@acme/core';
import { createMobileStore } from '../src/store/useStore';
import { memoryStorage } from '../src/utils/storage';

const validInput: ReleaseInput = {
  title: '移动端灰度发布',
  appId: 'mobile',
  version: '1.4.0',
  env: 'test',
  owner: '苏然',
  priority: 1,
  notes: '',
};

let storage: StorageLike;
let store: ReturnType<typeof createMobileStore>;

beforeEach(() => {
  storage = memoryStorage();
  store = createMobileStore(storage);
});

describe('createMobileStore（与 Web 同构，共享 core）', () => {
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
    expect(store.getState().state.releases[0].platform).toBe('mobile');
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

  it('advance 推进状态', () => {
    const draft = store.getState().state.releases.find((r) => r.status === 'draft')!;
    const ok = store.getState().advance(draft.id);
    expect(ok).toBe(true);
    expect(store.getState().state.releases.find((r) => r.id === draft.id)!.status).toBe('building');
  });

  it('删除后可撤销恢复', () => {
    const r = store.getState().state.releases[0];
    const index = store.getState().state.releases.findIndex((x) => x.id === r.id);
    store.getState().dispatch({ type: 'DELETE_RELEASES', ids: [r.id] });
    expect(store.getState().state.releases.some((x) => x.id === r.id)).toBe(false);
    store.getState().dispatch({ type: 'RESTORE_RELEASES', items: [{ release: r, index }] });
    expect(store.getState().state.releases.some((x) => x.id === r.id)).toBe(true);
  });

  it('applyView 应用保存视图', () => {
    store.getState().applyView('view-prod');
    expect(store.getState().query.env).toBe('prod');
    expect(store.getState().query.sort).toBe('updated');
  });

  it('UPDATE_SETTINGS 更新强调色', () => {
    store.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: 'magenta' } });
    expect(store.getState().state.settings.accent).toBe('magenta');
  });

  it('resetData 恢复种子', () => {
    store.getState().dispatch({ type: 'CREATE_RELEASE', payload: validInput });
    store.getState().resetData();
    expect(store.getState().state.releases).toHaveLength(14);
  });
});