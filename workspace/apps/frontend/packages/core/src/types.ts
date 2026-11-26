import type { ThemePreferences } from './preferences';

export type Platform = 'web' | 'desktop' | 'mobile';
export type Health = 'healthy' | 'warning' | 'critical';
export type Tone = 'muted' | 'info' | 'violet' | 'warning' | 'success' | 'danger';
export type Theme = 'dark' | 'light';
export type Density = 'comfortable' | 'compact';
/** 圆角档位（REQ-20261121-043502 THEME-2 v3）：medium=现状 RADII 基线；small/large 按档位系数缩放；full=胶囊。 */
export type RadiusScale = 'small' | 'medium' | 'large' | 'full';
/** 内容宽度档位（REQ-20261121-043502 THEME-2 v3）：fluid=现状不限宽；compact=内容区定宽居中。 */
export type ContentWidth = 'fluid' | 'compact';
/** 布局模式（REQ-20261122-233427 THEME-4 v4 确认②③）：sidebar=侧边导航（现状）；
 *  top=顶栏主导航+全宽内容；mixed=顶栏一级横向导航+左侧 72px 图标轨。 */
export type LayoutMode = 'sidebar' | 'top' | 'mixed';
/** 页签风格（THEME-4 v4 确认④）：line=VS Code 线条（现状缺省）；card=卡片块；chrome=梯形标签页；plain=纯文字+分隔线。 */
export type TabStyle = 'line' | 'card' | 'chrome' | 'plain';
/** 侧栏色调（THEME-4 v4 确认⑤）：auto=跟随主题（现状）；dark=浅色模式下侧栏/顶栏取暗色中性盘子派生半深色（暗色模式无效）。 */
export type SiderTone = 'auto' | 'dark';
/** 顶栏 widget 键位（THEME-4 v4 确认②，缺省全显）：与租户级水印/页脚控制正交（个人偏好级）。
 *  REQ-20261126-125836：release 已随发布单演示域删除；历史偏好中的 legacy 键由校验器接受但忽略。 */
export type TopbarWidgetKey =
  | 'search'
  | 'bell'
  | 'lang'
  | 'timezone'
  | 'appearance'
  | 'themeToggle'
  | 'lock'
  | 'fullscreen'
  | 'settings';
/** 顶栏 widget 显隐开关（THEME-4 v4 确认②）：键位全集固定，布尔缺省 true=显示。 */
export type TopbarWidgets = Record<TopbarWidgetKey, boolean>;
export type Accent =
  | 'cyan'
  | 'blue'
  | 'violet'
  | 'magenta'
  | 'amber'
  | 'acid'
  | 'emerald'
  | 'sky'
  | 'coral'
  | 'ruby'
  | 'bronze'
  | 'slate';

export interface App {
  id: string;
  name: string;
  platform: Platform;
  path: string;
  version: string;
  owner: string;
  health: Health;
  description: string;
}

export interface Settings {
  /** 偏好子结构版本标记（PREFERENCES_VERSION）：迁移与校验唯一入口见 preferences.ts。 */
  schemaVersion: number;
  /** 主题偏好唯一事实源（REQ-20261121-010725）：三端全部读取路径必须走此字段。
   *  REQ-20261121-200415 THEME-3 契约⑧：@deprecated 扁平投影字段（theme/accent/density/motion）
   *  已物理删除，settings = { schemaVersion, preferences }（唯一构造入口 buildSettings）。 */
  preferences: ThemePreferences;
}

/** 多端基座状态：仅保留 apps（三端清单）与 settings（主题/偏好），无业务演示域字段。 */
export interface AppState {
  version: number;
  apps: App[];
  settings: Settings;
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

export type Action =
  | { type: 'UPDATE_SETTINGS'; patch: Partial<Settings> }
  | { type: 'UPDATE_APP'; id: string; patch: Partial<App> }
  | { type: 'IMPORT'; data: unknown }
  | { type: 'RESET' };
