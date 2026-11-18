import { create } from 'zustand';

/**
 * 页签 store（REQ-20261119-063129 契约②）：多页签打开/激活/关闭/固定语义 +
 * 手写持久化 `acme.tabs.v1`（会话级页签还原；损坏回退缺省，范本=tableSettings）。
 *
 * 形状：{ tabs: { path, label?, labelKey?, pinned? }[], activePath }。
 * - openTab：存在即激活（label 元数据随动态菜单刷新），不存在追加并激活；
 * - closeTab：pinned 不可关；关激活页切相邻（优先右侧，无右侧取左侧）；
 * - closeOthers/closeRight/closeAll：pinned 一律保留；
 * - togglePin：固定前置（pinned 排 tabs 队首）且不可关；
 * - refreshKeys：会话态重挂计数（右键刷新 → key++），不持久化；
 * - hydrate/clear：App 登录态切换时还原/清空（clear 同时移除存储键，AC-7）。
 * label 渲染优先级：打开时动态菜单 menuName 快照（label）> labelKey 映射 > path（TabBar 解析）。
 */

const STORAGE_KEY = 'acme.tabs.v1';

export interface TabItem {
  path: string;
  /** 打开时上下文动态菜单 menuName 快照（优先级最高）。 */
  label?: string;
  /** i18n 键回退（PAGE_ROUTES labelKey）。 */
  labelKey?: string;
  pinned?: boolean;
}

interface TabsPersistShape {
  tabs: TabItem[];
  activePath: string | null;
}

export interface TabsState extends TabsPersistShape {
  /** 每 tab 重挂计数（会话态，不持久化）。 */
  refreshKeys: Record<string, number>;
  openTab: (path: string, meta?: { label?: string; labelKey?: string }) => void;
  /** 关闭单页签；返回关闭后的 activePath（供 App 对齐 hash）。 */
  closeTab: (path: string) => string | null;
  closeOthers: (path: string) => string | null;
  closeRight: (path: string) => string | null;
  closeAll: () => string | null;
  togglePin: (path: string) => void;
  setActive: (path: string) => void;
  /** 右键刷新：refreshKey++ → TabKeepAlive 容器 key 变化强制重挂该页（契约④）。 */
  refresh: (path: string) => void;
  /** 登录/新会话：从持久化还原（新会话=空，App 随后按当前 path 打开首签）。 */
  hydrate: () => void;
  /** 登出：清空页签并移除存储键（AC-7）。 */
  clear: () => void;
}

/** 运行时校验单条页签（外部输入：localStorage，禁类型断言替代）。 */
function isTabItem(value: unknown): value is TabItem {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.path !== 'string' || candidate.path === '') return false;
  if (candidate.label !== undefined && typeof candidate.label !== 'string') return false;
  if (candidate.labelKey !== undefined && typeof candidate.labelKey !== 'string') return false;
  if (candidate.pinned !== undefined && typeof candidate.pinned !== 'boolean') return false;
  return true;
}

/** 读取持久化页签：损坏/未知形状一律回退缺省（不覆盖原存储）；activePath 必须命中已存页签。 */
function loadPersisted(): TabsPersistShape {
  const empty: TabsPersistShape = { tabs: [], activePath: null };
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return empty;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return empty;
    const candidate = parsed as { tabs?: unknown; activePath?: unknown };
    if (!Array.isArray(candidate.tabs)) return empty;
    const tabs = candidate.tabs.filter(isTabItem);
    const activePath = typeof candidate.activePath === 'string' && tabs.some((tab) => tab.path === candidate.activePath)
      ? candidate.activePath
      : (tabs[0]?.path ?? null);
    return { tabs, activePath };
  } catch {
    return empty;
  }
}

function persist(tabs: TabItem[], activePath: string | null): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ tabs, activePath }));
  } catch {
    // 存储不可用（隐私模式/配额）：页签退化为会话内存态，不阻塞交互
  }
}

/** 关激活页的相邻页签：优先右侧（同下标的新数组首位），无右侧取左侧。 */
function neighborOf(tabs: TabItem[], removedIndex: number): string | null {
  return tabs[removedIndex]?.path ?? tabs[removedIndex - 1]?.path ?? null;
}

export const useTabsStore = create<TabsState>()((set, get) => ({
  ...loadPersisted(),
  refreshKeys: {},

  openTab: (path, meta) => {
    const { tabs } = get();
    const existing = tabs.find((tab) => tab.path === path);
    if (existing) {
      // 存在即激活；动态菜单上下文有新 label 时同步刷新元数据
      const label = meta?.label ?? existing.label;
      const labelKey = meta?.labelKey ?? existing.labelKey;
      if (label !== existing.label || labelKey !== existing.labelKey) {
        set({ tabs: tabs.map((tab) => (tab.path === path ? { ...tab, label, labelKey } : tab)), activePath: path });
        persist(get().tabs, path);
        return;
      }
      if (get().activePath !== path) {
        set({ activePath: path });
        persist(get().tabs, path);
      }
      return;
    }
    const nextTabs = [...tabs, { path, label: meta?.label, labelKey: meta?.labelKey }];
    set({ tabs: nextTabs, activePath: path });
    persist(nextTabs, path);
  },

  closeTab: (path) => {
    const { tabs, activePath } = get();
    const index = tabs.findIndex((tab) => tab.path === path);
    if (index < 0) return activePath;
    if (tabs[index]?.pinned) return activePath; // pinned 不可关
    const nextTabs = tabs.filter((tab) => tab.path !== path);
    const nextActive = activePath === path ? neighborOf(nextTabs, index) : activePath;
    set({ tabs: nextTabs, activePath: nextActive });
    persist(nextTabs, nextActive);
    return nextActive;
  },

  closeOthers: (path) => {
    const { tabs } = get();
    const target = tabs.find((tab) => tab.path === path);
    if (!target) return get().activePath;
    const nextTabs = tabs.filter((tab) => tab.path === path || tab.pinned);
    set({ tabs: nextTabs, activePath: path });
    persist(nextTabs, path);
    return path;
  },

  closeRight: (path) => {
    const { tabs, activePath } = get();
    const index = tabs.findIndex((tab) => tab.path === path);
    if (index < 0) return activePath;
    const right = tabs.slice(index + 1).filter((tab) => !tab.pinned);
    if (right.length === 0) return activePath;
    const nextTabs = tabs.filter((tab) => tab.path === path || !right.includes(tab));
    const nextActive = activePath && right.some((tab) => tab.path === activePath) ? path : activePath;
    set({ tabs: nextTabs, activePath: nextActive });
    persist(nextTabs, nextActive);
    return nextActive;
  },

  closeAll: () => {
    const nextTabs = get().tabs.filter((tab) => tab.pinned);
    const nextActive = nextTabs[0]?.path ?? null;
    set({ tabs: nextTabs, activePath: nextActive });
    persist(nextTabs, nextActive);
    return nextActive;
  },

  togglePin: (path) => {
    const toggled = get().tabs.map((tab) => (tab.path === path ? { ...tab, pinned: !tab.pinned } : tab));
    const target = toggled.find((tab) => tab.path === path);
    // 固定语义=pinned 前置（契约③）；取消固定保持原位
    const nextTabs = target?.pinned
      ? [target, ...toggled.filter((tab) => tab.path !== path)]
      : toggled;
    set({ tabs: nextTabs });
    persist(nextTabs, get().activePath);
  },

  setActive: (path) => {
    if (get().activePath === path) return;
    if (!get().tabs.some((tab) => tab.path === path)) return;
    set({ activePath: path });
    persist(get().tabs, path);
  },

  refresh: (path) => {
    const { refreshKeys } = get();
    set({ refreshKeys: { ...refreshKeys, [path]: (refreshKeys[path] ?? 0) + 1 } });
  },

  hydrate: () => {
    const restored = loadPersisted();
    set({ tabs: restored.tabs, activePath: restored.activePath, refreshKeys: {} });
  },

  clear: () => {
    set({ tabs: [], activePath: null, refreshKeys: {} });
    try {
      window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      // 存储不可用：内存态已清空即可
    }
  },
}));
