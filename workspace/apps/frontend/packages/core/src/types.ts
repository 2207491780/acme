import type { ThemePreferences } from './preferences';

export type Platform = 'web' | 'desktop' | 'mobile';
export type Env = 'dev' | 'test' | 'prod';
export type Status =
  | 'draft'
  | 'building'
  | 'testing'
  | 'approved'
  | 'published'
  | 'failed'
  | 'cancelled';
export type Health = 'healthy' | 'warning' | 'critical';
export type SortKey = 'manual' | 'updated' | 'priority' | 'version' | 'title';
export type Tone = 'muted' | 'info' | 'violet' | 'warning' | 'success' | 'danger';
export type Theme = 'dark' | 'light';
export type Density = 'comfortable' | 'compact';
/** 圆角档位（REQ-20261121-043502 THEME-2 v3）：medium=现状 RADII 基线；small/large 按档位系数缩放；full=胶囊。 */
export type RadiusScale = 'small' | 'medium' | 'large' | 'full';
/** 内容宽度档位（REQ-20261121-043502 THEME-2 v3）：fluid=现状不限宽；compact=内容区定宽居中。 */
export type ContentWidth = 'fluid' | 'compact';
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

export interface Artifact {
  name: string;
  size: string;
}

export interface TimelineEntry {
  at: string;
  action: string;
  by: string;
}

export interface Release {
  id: string;
  title: string;
  appId: string;
  platform: Platform;
  version: string;
  env: Env;
  status: Status;
  priority: number;
  owner: string;
  progress: number;
  notes: string;
  artifacts: Artifact[];
  timeline: TimelineEntry[];
  createdAt: string;
  updatedAt: string;
}

export interface Query {
  search: string;
  platform: Platform | 'all';
  status: Status | 'active' | 'all';
  env: Env | 'all';
  sort: SortKey;
}

export interface SavedView {
  id: string;
  name: string;
  query: Query;
}

export interface Settings {
  /** 偏好子结构版本标记（PREFERENCES_VERSION）：迁移与校验唯一入口见 preferences.ts。 */
  schemaVersion: number;
  /** 主题偏好唯一事实源（REQ-20261121-010725）：三端全部读取路径必须走此字段。
   *  REQ-20261121-200415 THEME-3 契约⑧：@deprecated 扁平投影字段（theme/accent/density/motion）
   *  已物理删除，settings = { schemaVersion, preferences }（唯一构造入口 buildSettings）。 */
  preferences: ThemePreferences;
}

export interface Activity {
  id: string;
  at: string;
  text: string;
  tone: Tone;
}

export interface AppState {
  version: number;
  apps: App[];
  releases: Release[];
  order: string[];
  savedViews: SavedView[];
  settings: Settings;
  activity: Activity[];
}

export interface ReleaseInput {
  title: string;
  appId: string;
  version: string;
  env: Env;
  owner: string;
  priority: number | string;
  notes?: string;
}

export type ValidationErrors = Partial<
  Record<'title' | 'appId' | 'version' | 'env' | 'owner' | 'priority', string>
>;

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

export interface RestoreItem {
  release: Release;
  index: number;
}

export type Action =
  | { type: 'CREATE_RELEASE'; payload: ReleaseInput }
  | { type: 'UPDATE_RELEASE'; id: string; payload: ReleaseInput }
  | { type: 'DELETE_RELEASES'; ids: string[] }
  | { type: 'RESTORE_RELEASES'; items: RestoreItem[] }
  | { type: 'SET_STATUS'; id: string; status: Status; note?: string; by?: string }
  | { type: 'TICK' }
  | { type: 'REORDER'; sourceId: string; targetId: string }
  | { type: 'SAVE_VIEW'; name: string; query: Query }
  | { type: 'DELETE_VIEW'; id: string }
  | { type: 'UPDATE_SETTINGS'; patch: Partial<Settings> }
  | { type: 'UPDATE_APP'; id: string; patch: Partial<App> }
  | { type: 'IMPORT'; data: unknown }
  | { type: 'RESET' };

export interface Metrics {
  total: number;
  active: number;
  approved: number;
  failed: number;
  published: number;
  publishedToday: number;
  byStatus: Record<Status, number>;
  byPlatform: Record<Platform, number>;
}