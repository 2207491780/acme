import { create } from 'zustand';
import { ApiError } from '../http/client';
import { systemApi, type AdminMeView } from '../api/system';
import { useAuthStore } from './authStore';

/**
 * 当前登录者服务端画像（p1-me-sync）：随登录态加载，作为会话恢复校验与
 * 顶栏身份/角色/权限的真实来源；不持久化（每次进入应用重新拉取）。
 * 加载失败降级：非鉴权错误保留本地会话身份渲染（不阻塞控制台）；
 * 403（已认证但不可用，如账号停用）→ 清会话回登录页；401 由 api client 续约/清会话处理。
 * REQ-20261121-054613：me.capabilities（当前租户生效能力码集）随 AdminMeView 整体透传存取，
 * 本 store 不消费（暂无 UI 消费点）——消费方=WS-BPM/WS-JOB 页面特性开关（后续轮次接线）。
 */
interface MeStore {
  me: AdminMeView | null;
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load: () => Promise<void>;
  clear: () => void;
}

export const useMeStore = create<MeStore>()((set, get) => ({
  me: null,
  loading: false,
  loaded: false,
  error: null,
  load: async () => {
    if (get().loading) {
      return;
    }
    set({ loading: true, error: null });
    try {
      const me = await systemApi.me();
      set({ me, loading: false, loaded: true, error: null });
    } catch (ex: unknown) {
      const error = ex instanceof ApiError ? ex : new Error('当前用户信息加载失败');
      set({ loading: false, error: error.message });
      if (ex instanceof ApiError && ex.status === 403) {
        useAuthStore.getState().clear();
        window.location.hash = '#/login';
      }
    }
  },
  clear: () => set({ me: null, loading: false, loaded: false, error: null }),
}));
