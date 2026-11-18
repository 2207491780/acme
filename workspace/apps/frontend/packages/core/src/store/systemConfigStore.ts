import { create } from 'zustand';
import { systemApi, type AdminSystemConfigUpdate, type AdminSystemConfigView } from '../api/system';
import { useLockStore } from './lockStore';

/**
 * 租户系统配置 store（REQ-20261116-230354）：水印开关 / 登录验证码开关（REQ-20261117-011514）/
 * 闲置锁屏时长 / 页脚版权（REQ-20261117-025200）/ 水印模板（REQ-20261117-180000）的唯一事实源，
 * 内存态不持久化——登录/会话恢复引导时随 me() 经 load() 拉取，租户配置对全员强制生效。
 * 副作用：lockIdleTimeoutMs → useLockStore.setIdleTimeoutMs（武装/解除闲置计时）；
 * 水印显隐与内容由消费方（web App 全局水印）订阅 watermarkEnabled/watermarkTemplate 响应式联动；
 * 页脚显隐由消费方（web App 布局 Footer）订阅 footerVisible/footerCopyright 响应式联动；
 * captchaEnabled 仅供系统设置页回显（登录页走 captchaPolicy 公开端点实时取策略）。
 * 缺省值（契约确认记录④ + REQ-20261117-011514 V16 内置默认 + REQ-20261117-025200 V17 内置默认
 * + REQ-20261117-180000 V18 内置默认）：
 * 水印开 / 验证码关 / 闲置锁屏关 / 页脚不显示（copyright=null）/ 水印模板 null（仅用户名）/ version "0"。
 */

interface SystemConfigState {
  watermarkEnabled: boolean;
  captchaEnabled: boolean;
  lockIdleTimeoutMs: number;
  footerCopyright: string | null;
  footerVisible: boolean;
  watermarkTemplate: string | null;
  version: string;
  loaded: boolean;
  load: () => Promise<void>;
  update: (payload: AdminSystemConfigUpdate) => Promise<AdminSystemConfigView>;
}

export const useSystemConfigStore = create<SystemConfigState>()((set) => ({
  watermarkEnabled: true,
  captchaEnabled: false,
  lockIdleTimeoutMs: 0,
  footerCopyright: null,
  footerVisible: false,
  watermarkTemplate: null,
  version: '0',
  loaded: false,

  load: async () => {
    try {
      const config = await systemApi.configGet();
      set({
        watermarkEnabled: config.watermarkEnabled,
        captchaEnabled: config.captchaEnabled,
        lockIdleTimeoutMs: config.lockIdleTimeoutMs,
        footerCopyright: config.footerCopyright,
        footerVisible: config.footerVisible,
        watermarkTemplate: config.watermarkTemplate,
        version: config.version,
        loaded: true,
      });
      useLockStore.getState().setIdleTimeoutMs(config.lockIdleTimeoutMs);
    } catch {
      // 加载失败保持缺省（水印开/验证码关/闲置锁屏关/页脚不显示/模板仅用户名），不阻塞控制台（meStore 降级口径）
    }
  },

  update: async (payload) => {
    const view = await systemApi.configUpdate(payload);
    set({
      watermarkEnabled: view.watermarkEnabled,
      captchaEnabled: view.captchaEnabled,
      lockIdleTimeoutMs: view.lockIdleTimeoutMs,
      footerCopyright: view.footerCopyright,
      footerVisible: view.footerVisible,
      watermarkTemplate: view.watermarkTemplate,
      version: view.version,
      loaded: true,
    });
    useLockStore.getState().setIdleTimeoutMs(view.lockIdleTimeoutMs);
    return view;
  },
}));
