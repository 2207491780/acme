import { create } from 'zustand';
import { systemApi, type AdminSystemConfigView } from '../api/system';
import { useLockStore } from './lockStore';

/**
 * 租户系统配置 store（REQ-20261116-230354）：水印开关 / 闲置锁屏时长的唯一事实源，
 * 内存态不持久化——登录/会话恢复引导时随 me() 经 load() 拉取，租户配置对全员强制生效。
 * 副作用：lockIdleTimeoutMs → useLockStore.setIdleTimeoutMs（武装/解除闲置计时）；
 * 水印显隐由消费方（web App 全局水印）订阅 watermarkEnabled 响应式联动。
 * 缺省值（契约确认记录④）：水印开 / 闲置锁屏关 / version "0"。
 */

interface SystemConfigState {
  watermarkEnabled: boolean;
  lockIdleTimeoutMs: number;
  version: string;
  loaded: boolean;
  load: () => Promise<void>;
  update: (payload: { watermarkEnabled: boolean; lockIdleTimeoutMs: number; version: string }) => Promise<AdminSystemConfigView>;
}

export const useSystemConfigStore = create<SystemConfigState>()((set) => ({
  watermarkEnabled: true,
  lockIdleTimeoutMs: 0,
  version: '0',
  loaded: false,

  load: async () => {
    try {
      const config = await systemApi.configGet();
      set({
        watermarkEnabled: config.watermarkEnabled,
        lockIdleTimeoutMs: config.lockIdleTimeoutMs,
        version: config.version,
        loaded: true,
      });
      useLockStore.getState().setIdleTimeoutMs(config.lockIdleTimeoutMs);
    } catch {
      // 加载失败保持缺省（水印开/闲置锁屏关），不阻塞控制台（meStore 降级口径）
    }
  },

  update: async (payload) => {
    const view = await systemApi.configUpdate(payload);
    set({
      watermarkEnabled: view.watermarkEnabled,
      lockIdleTimeoutMs: view.lockIdleTimeoutMs,
      version: view.version,
      loaded: true,
    });
    useLockStore.getState().setIdleTimeoutMs(view.lockIdleTimeoutMs);
    return view;
  },
}));