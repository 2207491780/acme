import { create } from 'zustand';
import {
  loadState,
  saveState,
  parseState,
  seedState,
  STORAGE_KEY,
  reduce,
  filterReleases,
  sortReleases,
  NEXT_STATUS,
} from '@acme/core';
import type { Action, AppState, Query, Release, RestoreItem, StorageLike } from '@acme/core';
import { browserStorage } from '../utils/dom';

export type ModalState =
  | { type: 'release'; id: string | null }
  | { type: 'savedView' }
  | { type: 'app'; id: string };

export const DEFAULT_QUERY: Query = {
  search: '',
  platform: 'all',
  status: 'all',
  env: 'all',
  sort: 'manual',
};

export interface AppStore {
  state: AppState;
  storageError: boolean;
  storageConflict: boolean;
  query: Query;
  selection: string[];
  drawerId: string | null;
  modal: ModalState | null;
  dispatch: (action: Action) => boolean;
  setQuery: (patch: Partial<Query>) => void;
  resetQuery: () => void;
  applyView: (id: string) => void;
  toggleSelect: (id: string, checked: boolean) => void;
  setSelection: (ids: string[]) => void;
  clearSelection: () => void;
  openDrawer: (id: string) => void;
  closeDrawer: () => void;
  openModal: (m: ModalState) => void;
  closeModal: () => void;
  advance: (id: string) => boolean;
  bulkAdvance: () => number;
  deleteReleases: (ids: string[]) => RestoreItem[];
  resetData: () => boolean;
  visibleReleases: () => Release[];
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
  return create<AppStore>()((set, get) => ({
    state: initialState,
    storageError,
    storageConflict: false,
    query: { ...DEFAULT_QUERY },
    selection: [],
    drawerId: null,
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
            const modal = cur.modal;
            const releaseModal = modal?.type === 'release' ? modal : null;
            const appModal = modal?.type === 'app' ? modal : null;
            const modalKept = releaseModal
              ? state.releases.some((r) => r.id === releaseModal.id)
              : appModal
                ? state.apps.some((a) => a.id === appModal.id)
                : true;
            set({
              state,
              selection: [],
              storageConflict: true,
              drawerId: cur.drawerId && state.releases.some((r) => r.id === cur.drawerId) ? cur.drawerId : null,
              modal: modalKept ? modal : null,
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
        storageConflict: action.type === 'TICK' ? get().storageConflict : false,
        ...(action.type === 'IMPORT' || action.type === 'RESET'
          ? { query: { ...DEFAULT_QUERY }, selection: [], drawerId: null, modal: null }
          : {}),
      });
      return true;
    },

    setQuery: (patch) => set((s) => ({ query: { ...s.query, ...patch }, selection: [] })),
    resetQuery: () => set({ query: { ...DEFAULT_QUERY }, selection: [] }),
    applyView: (id) => {
      const v = get().state.savedViews.find((x) => x.id === id);
      if (v) set({ query: { ...v.query }, selection: [] });
    },

    toggleSelect: (id, checked) =>
      set((s) => ({
        selection: checked
          ? [...new Set([...s.selection, id])]
          : s.selection.filter((x) => x !== id),
      })),
    setSelection: (ids) => set({ selection: ids }),
    clearSelection: () => set({ selection: [] }),

    openDrawer: (id) => set({ drawerId: id }),
    closeDrawer: () => set({ drawerId: null }),
    openModal: (m) => set({ modal: m }),
    closeModal: () => set({ modal: null }),

    advance: (id) => {
      const r = get().state.releases.find((x) => x.id === id);
      const next = r && NEXT_STATUS[r.status];
      if (!next) return false;
      return get().dispatch({ type: 'SET_STATUS', id, status: next });
    },

    bulkAdvance: () => {
      let count = 0;
      const visible = new Set(get().visibleReleases().map((r) => r.id));
      for (const id of get().selection.filter((id) => visible.has(id))) {
        const r = get().state.releases.find((x) => x.id === id);
        const next = r && NEXT_STATUS[r.status];
        if (next && get().dispatch({ type: 'SET_STATUS', id, status: next })) count += 1;
        if (get().storageConflict) break;
      }
      return count;
    },

    deleteReleases: (ids) => {
      const st = get().state;
      const items = ids
        .map((id) => ({
          release: st.releases.find((r) => r.id === id),
          index: st.releases.findIndex((r) => r.id === id),
        }))
        .filter((x): x is RestoreItem => Boolean(x.release));
      if (!items.length || !get().dispatch({ type: 'DELETE_RELEASES', ids })) return [];
      set((s) => ({
        selection: s.selection.filter((x) => !ids.includes(x)),
        drawerId: s.drawerId && ids.includes(s.drawerId) ? null : s.drawerId,
      }));
      return items;
    },

    resetData: () => get().dispatch({ type: 'RESET' }),

    visibleReleases: () => {
      const { state, query } = get();
      return sortReleases(filterReleases(state, query), query.sort, state.order);
    },
  }));
}

export const useStore = createAppStore(browserStorage);