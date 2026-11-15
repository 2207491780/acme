import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * UI 偏好 store（REQ-20261114-120112，AC-3/AC-4）：
 * 水印开关 / 闲置锁屏时长等轻量偏好，经 zustand persist 落 localStorage
 * （键名版本化 acme.ui-prefs.v1）。
 *
 * 与既有 settings 体系的对照：主题/密度/强调色/动效仍走既有 useStore.state.settings
 * （reducer UPDATE_SETTINGS 持久化，与演示数据同落点）；本 store 只承载本次新增的
 * 「体验组件偏好」，避免扩展既有 Settings 数据契约（seed/parse-state 面不动）。
 */

interface UiPrefsState {
  watermarkEnabled: boolean;
  /** 闲置锁屏时长（毫秒），0 = 关闭闲置锁屏。 */
  lockIdleTimeoutMs: number;
  setWatermarkEnabled: (enabled: boolean) => void;
  setLockIdleTimeoutMs: (ms: number) => void;
}

export const useUiPrefsStore = create<UiPrefsState>()(
  persist(
    (set) => ({
      watermarkEnabled: true, // 契约确认记录②：管理端默认开
      lockIdleTimeoutMs: 0,
      setWatermarkEnabled: (watermarkEnabled) => set({ watermarkEnabled }),
      setLockIdleTimeoutMs: (lockIdleTimeoutMs) => set({ lockIdleTimeoutMs }),
    }),
    {
      name: 'acme.ui-prefs.v1',
    },
  ),
);
