import { DEFAULT_PREFERENCES, buildSettings } from './preferences';
import type { AppState, App } from './types';

/** 多端基座种子：仅 apps 三端清单 + settings（主题偏好），不含任何发布演示域数据。 */
export function seedState(_now: number = Date.now()): AppState {
  const apps: App[] = [
    {
      id: 'web',
      name: 'Web 控制台',
      platform: 'web',
      path: 'workspace/apps/frontend/web',
      version: '1.4.2',
      owner: '林晚',
      health: 'healthy',
      description: '浏览器端页面与业务逻辑的唯一来源，桌面端复用其构建产物。',
    },
    {
      id: 'desktop',
      name: 'Electron 桌面端',
      platform: 'desktop',
      path: 'workspace/apps/frontend/desktop',
      version: '1.4.2',
      owner: '陈屿',
      health: 'healthy',
      description: 'Electron 套壳 Web 端，只维护主进程、预加载桥接与打包资源。',
    },
    {
      id: 'mobile',
      name: '移动端',
      platform: 'mobile',
      path: 'workspace/apps/frontend/mobile',
      version: '1.3.0',
      owner: '苏然',
      health: 'warning',
      description: 'React Native + Expo 移动入口，复用共享 core 业务层。',
    },
  ];

  return {
    version: 1,
    apps,
    // 种子外观默认保持深色（与 index.html 首绘默认一致，避免无 blob 冷启动闪变）；
    // 出厂默认偏好（恢复默认基准）= DEFAULT_PREFERENCES（mode light），见 preferences.ts。
    settings: buildSettings({ ...DEFAULT_PREFERENCES, mode: 'dark' }),
  };
}
