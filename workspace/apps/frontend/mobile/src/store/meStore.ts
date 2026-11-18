import { create } from 'zustand';
import type { AdminMeView } from '@acme/core';
import { ApiError, apiFetch } from '../api/client';
import { useAuthStore } from './authStore';

/**
 * 当前登录者服务端画像（REQ-20261114-120110 AMB-2/AMB-4 镜像实现）：接口语义逐条对齐
 * core `store/meStore.ts`（p1-me-sync）——随登录态加载，作为权限门禁与身份/角色/权限/菜单
 * 的真实来源；不持久化（每次进入应用重新拉取）。
 * 与 core 版的两处宿主差异（RN 无 window / 无 hash 路由）：
 * - 传输走 mobile 既有 apiFetch（mobile 会话 + API_BASE 拼接，/admin/v1/system/me 直接可用）；
 * - 403（已认证但不可用，如账号停用）→ 清 mobile 会话（SecureStore 一并清）回登录屏——
 *   App 门禁以 session 是否存在渲染登录屏，无需显式路由跳转。
 * 加载失败降级：非鉴权错误保留本地会话身份渲染（不阻塞控制台）；401 由 api client 续约/清会话处理。
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
      const me = await apiFetch<AdminMeView>('/admin/v1/system/me');
      set({ me, loading: false, loaded: true, error: null });
    } catch (ex: unknown) {
      const error = ex instanceof ApiError ? ex : new Error('当前用户信息加载失败');
      set({ loading: false, error: error.message });
      if (ex instanceof ApiError && ex.status === 403) {
        useAuthStore.getState().clear();
      }
    }
  },
  clear: () => set({ me: null, loading: false, loaded: false, error: null }),
}));
