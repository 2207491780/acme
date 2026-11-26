import { create } from 'zustand';
import {
  loadState,
  saveState,
  reduce,
} from '@acme/core';
import type { Action, AppState, StorageLike } from '@acme/core';
import { mobileStorage } from '../utils/storage';

export interface MobileStore {
  state: AppState;
  dispatch: (action: Action) => boolean;
  /** 冷启动 hydrate 后重跑 loadState（AsyncStorage 异步灌入镜像后调用）。 */
  reloadState: () => void;
  resetData: () => void;
}

// 与 Web 端同构：zustand 包裹 core 的纯业务 reducer，仅替换存储适配器。
// REQ-20261126-125836：发布单筛选态（query/savedViews/advance/visibleReleases）随演示功能删除。
export function createMobileStore(storage: StorageLike) {
  return create<MobileStore>()((set, get) => ({
    state: loadState(storage),

    dispatch: (action) => {
      const cur = get().state;
      const next = reduce(cur, action, Date.now());
      if (next === cur) return false;
      saveState(storage, next);
      set({ state: next });
      return true;
    },

    reloadState: () => {
      // 冷启动 hydrate 后按 core 同一管道重载：合法持久化数据恢复，非法数据回落种子（不覆盖有效状态）
      set({ state: loadState(mobileStorage) });
    },

    resetData: () => {
      get().dispatch({ type: 'RESET' });
    },
  }));
}

export const useStore = createMobileStore(mobileStorage);
