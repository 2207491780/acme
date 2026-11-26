import { create } from 'zustand';
import {
  loadState,
  saveState,
  parseState,
  seedState,
  STORAGE_KEY,
  PREFERENCES_VERSION,
  reduce,
} from '@acme/core';
import type { Action, AppState, StorageLike } from '@acme/core';
import { browserStorage } from '../utils/dom';

export type ModalState = { type: 'app'; id: string };

export interface AppStore {
  state: AppState;
  storageError: boolean;
  storageConflict: boolean;
  modal: ModalState | null;
  dispatch: (action: Action) => boolean;
  openModal: (m: ModalState) => void;
  closeModal: () => void;
  resetData: () => boolean;
}

export function createAppStore(storage: StorageLike) {
  let persisted: string | null = null;
  let storageError = false;
  try {
    persisted = storage.getItem(STORAGE_KEY);
  } catch {
    storageError = true;
  }
  const initialState = loadState({ getItem: () => persisted, setItem: () => {} });
  // 迁移接线（REQ-20261121-010725）：水合后按需落盘——冷启动（无 blob）写入种子态；
  // blob 内 settings 缺当前版本标记（legacy 扁平形状）时立即写回迁移结果（schemaVersion/
  // preferences 新形状首帧前就位）。已是当前版本的 blob 不重写（保持既有「多窗口字节级
  // 冲突检测」口径，boot 不制造假冲突）；损坏 blob 不写（core loadState 已回落种子，不覆盖原数据）。
  // REQ-20261126-125836：旧 blob 的 releases/order/savedViews/activity 由 parseState 接受但忽略，
  // 不参与写回，也不覆盖用户其它偏好。
  let shouldPersistOnHydrate = false;
  if (!storageError) {
    if (persisted === null) {
      shouldPersistOnHydrate = true;
    } else {
      try {
        const parsed = JSON.parse(persisted) as { settings?: { schemaVersion?: unknown } } | null;
        const settings = parsed !== null && typeof parsed === 'object' ? parsed.settings : undefined;
        shouldPersistOnHydrate = !(
          typeof settings === 'object'
          && settings !== null
          && settings.schemaVersion === PREFERENCES_VERSION
        );
      } catch {
        shouldPersistOnHydrate = false;
      }
    }
  }
  if (shouldPersistOnHydrate && saveState(storage, initialState)) {
    persisted = JSON.stringify(initialState);
  }
  return create<AppStore>()((set, get) => ({
    state: initialState,
    storageError,
    storageConflict: false,
    modal: null,

    dispatch: (action) => {
      try {
        const latest = storage.getItem(STORAGE_KEY);
        if (latest !== persisted) {
          if (get().storageError) {
            set({ storageConflict: true });
          } else {
            let state: AppState | null = null;
            try {
              state = latest === null ? seedState() : parseState(JSON.parse(latest));
            } catch {
              set({ storageConflict: true });
              return false;
            }
            if (!state) {
              set({ storageConflict: true });
              return false;
            }
            persisted = latest;
            // 冲突重载后清掉指向已不存在实体的瞬态引用（与 IMPORT/RESET 的清理一致）。
            const cur = get();
            const appModal = cur.modal?.type === 'app' ? cur.modal : null;
            const modalKept = appModal
              ? state.apps.some((a) => a.id === appModal.id)
              : true;
            set({
              state,
              storageConflict: true,
              modal: modalKept ? cur.modal : null,
            });
          }
          return false;
        }
      } catch {
        set({ storageError: true });
      }
      const cur = get().state;
      const next = reduce(cur, action, Date.now());
      if (next === cur) return false;
      const saved = saveState(storage, next);
      if (saved) persisted = JSON.stringify(next);
      set({
        state: next,
        storageError: !saved,
        storageConflict: false,
        ...(action.type === 'IMPORT' || action.type === 'RESET' ? { modal: null } : {}),
      });
      return true;
    },

    openModal: (m) => set({ modal: m }),
    closeModal: () => set({ modal: null }),

    resetData: () => get().dispatch({ type: 'RESET' }),
  }));
}

export const useStore = createAppStore(browserStorage);
