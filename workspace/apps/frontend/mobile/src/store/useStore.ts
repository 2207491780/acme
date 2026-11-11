import { create } from 'zustand';
import {
  loadState,
  saveState,
  reduce,
  filterReleases,
  sortReleases,
  NEXT_STATUS,
} from '@acme/core';
import type { Action, AppState, Query, Release, StorageLike } from '@acme/core';
import { mobileStorage } from '../utils/storage';

export const DEFAULT_QUERY: Query = {
  search: '',
  platform: 'all',
  status: 'all',
  env: 'all',
  sort: 'manual',
};

export interface MobileStore {
  state: AppState;
  query: Query;
  dispatch: (action: Action) => boolean;
  setQuery: (patch: Partial<Query>) => void;
  resetQuery: () => void;
  applyView: (id: string) => void;
  advance: (id: string) => boolean;
  resetData: () => void;
  visibleReleases: () => Release[];
}

// 与 Web 端同构：zustand 包裹 core 的纯业务 reducer，仅替换存储适配器。
export function createMobileStore(storage: StorageLike) {
  return create<MobileStore>()((set, get) => ({
    state: loadState(storage),
    query: { ...DEFAULT_QUERY },

    dispatch: (action) => {
      const cur = get().state;
      const next = reduce(cur, action, Date.now());
      if (next === cur) return false;
      saveState(storage, next);
      set({ state: next });
      return true;
    },

    setQuery: (patch) => set((s) => ({ query: { ...s.query, ...patch } })),
    resetQuery: () => set({ query: { ...DEFAULT_QUERY } }),
    applyView: (id) => {
      const v = get().state.savedViews.find((x) => x.id === id);
      if (v) set({ query: { ...v.query } });
    },

    advance: (id) => {
      const r = get().state.releases.find((x) => x.id === id);
      const next = r && NEXT_STATUS[r.status];
      if (!next) return false;
      return get().dispatch({ type: 'SET_STATUS', id, status: next });
    },

    resetData: () => {
      get().dispatch({ type: 'RESET' });
    },

    visibleReleases: () => {
      const { state, query } = get();
      return sortReleases(filterReleases(state, query), query.sort, state.order);
    },
  }));
}

export const useStore = createMobileStore(mobileStorage);