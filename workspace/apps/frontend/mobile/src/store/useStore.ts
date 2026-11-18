import { create } from 'zustand';
import {
  loadState,
  saveState,
  reduce,
  filterReleases,
  sortReleases,
  isQuery,
  NEXT_STATUS,
} from '@acme/core';
import type { Action, AppState, Query, Release, StorageLike } from '@acme/core';
import { QUERY_STORAGE_KEY, mobileStorage } from '../utils/storage';

export const DEFAULT_QUERY: Query = {
  search: '',
  platform: 'all',
  status: 'all',
  env: 'all',
  sort: 'manual',
};

/** 筛选态持久化键（契约②业务态入 AsyncStorage；登记处与冷启动灌入键集见 utils/storage.ts）。 */
export { QUERY_STORAGE_KEY };

/** 冷启动恢复筛选态：经 isQuery 运行时校验（parse-state 同一标准），非法数据回落缺省且不回写。 */
export function loadQuery(storage: StorageLike): Query {
  try {
    const raw = storage.getItem(QUERY_STORAGE_KEY);
    if (!raw) return { ...DEFAULT_QUERY };
    const parsed: unknown = JSON.parse(raw);
    return isQuery(parsed) ? parsed : { ...DEFAULT_QUERY };
  } catch {
    return { ...DEFAULT_QUERY };
  }
}

function saveQuery(storage: StorageLike, query: Query): void {
  storage.setItem(QUERY_STORAGE_KEY, JSON.stringify(query));
}

export interface MobileStore {
  state: AppState;
  query: Query;
  dispatch: (action: Action) => boolean;
  setQuery: (patch: Partial<Query>) => void;
  resetQuery: () => void;
  /** 冷启动 hydrate 后重跑 loadState/loadQuery（AsyncStorage 异步灌入镜像后调用）。 */
  reloadState: () => void;
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

    setQuery: (patch) =>
      set((s) => {
        const query = { ...s.query, ...patch };
        saveQuery(mobileStorage, query);
        return { query };
      }),
    resetQuery: () => {
      saveQuery(mobileStorage, { ...DEFAULT_QUERY });
      set({ query: { ...DEFAULT_QUERY } });
    },
    reloadState: () => {
      // 冷启动 hydrate 后按 core 同一管道重载：合法持久化数据恢复，非法数据回落种子（不覆盖有效状态）
      set({ state: loadState(mobileStorage), query: loadQuery(mobileStorage) });
    },
    applyView: (id) => {
      const v = get().state.savedViews.find((x) => x.id === id);
      if (v) {
        saveQuery(mobileStorage, { ...v.query });
        set({ query: { ...v.query } });
      }
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