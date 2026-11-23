/**
 * REQ-20261122-233427（THEME-4）AC-3 页签四风格 + AC-4 拖拽排序：web 组件用例。
 *
 * 表面 = 契约确认记录④ + AC-3/AC-4 + 委派单钉死事实：
 *   - tabStyle('line'|'card'|'chrome'|'plain')：line=现状 VS Code 风（缺省）；card=卡片块+活动底色；
 *     chrome=梯形标签页隐喻；plain=纯文字+分隔线——同一 tabsStore/行为面不变（pin/关闭/溢出滚动/预览卡全保留）；
 *   - 拖拽排序=@dnd-kit/core+@dnd-kit/sortable（契约④钉死依赖）：仅重排 tabs 顺序，持久化随
 *     tabsStore 现有 hydrate 机制生效（acme.tabs.v1）；permanent（仪表盘）不锁拖拽但拖拽后仍在集合内。
 *
 * 装配照范本 web/tests/REQ20261120104022-tabbar-scroll-preview.test.tsx（App 整棵 + hash 导航播种 +
 * 右键菜单交互 + 预览卡 Portal 定位），页签/卡片 label 断言收敛作用域。
 *
 * @dnd-kit 在 jsdom 下的断言口径（委派单预授权「必要时 vi.mock 拖拽库做行为面断言并注明」）：
 *   jsdom 无布局与 pointer 捕获管线，真实拖拽模拟不可行 → vi.mock 两个拖拽库为直通桩：
 *   DndContext 渲染 children 并捕获实现注册的 onDragEnd（标准 DragEndEvent 形状
 *   { active:{id}, over:{id}|null }），用例以按钮级 fireDragEnd 驱动重排，断言只落在
 *   行为面（store 顺序/DOM 顺序/acme.tabs.v1 持久化），不耦合 dnd-kit 内部指针管线。
 *   校准注记：sortable item id 假定为页签 path（稳定身份语义）；若实现用索引 id，自验轮改 fire 参数。
 *
 * 视觉面锚点校准注记（实现交付后自验轮收敛）：风格承载面（bar/tab 的 data-attribute 或类名）
 * 契约未钉死 → styleDiscriminator 宽容扫描 data-tab-style/类名任一承载；CSS 面走 global.css
 * 源块解析（样式 token × 页签上下文规则至少一条）。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import { useTabsStore } from '../src/store/tabsStore';
import {
  DEFAULT_PREFERENCES,
  useAuthStore,
  useLockStore,
  useMeStore,
  useNotifyStore,
  type Settings,
} from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

// ————— dnd-kit 直通桩（行为面断言口径，见文件头） —————

type DragEndEventLike = { active: { id: unknown }; over: { id: unknown } | null };

const dndRegistry = vi.hoisted(() => ({
  onDragEnd: null as null | ((event: { active: { id: unknown }; over: { id: unknown } | null }) => void),
}));

vi.mock('@dnd-kit/core', async () => {
  const { createElement, Fragment } = await import('react');
  return {
    DndContext: (props: { children?: ReactNode; onDragEnd?: (event: DragEndEventLike) => void }) => {
      dndRegistry.onDragEnd = props.onDragEnd ?? null;
      return createElement(Fragment, null, props.children ?? null);
    },
    DragOverlay: (props: { children?: ReactNode }) => createElement(Fragment, null, props.children ?? null),
    PointerSensor: class PointerSensor {},
    KeyboardSensor: class KeyboardSensor {},
    MouseSensor: class MouseSensor {},
    TouchSensor: class TouchSensor {},
    useSensor: (sensor: unknown) => sensor,
    useSensors: (...sensors: unknown[]) => sensors,
    closestCenter: (x: unknown) => x,
    closestCorners: (x: unknown) => x,
    pointerWithin: (x: unknown) => x,
    rectIntersection: (x: unknown) => x,
    useDraggable: () => ({
      attributes: {}, listeners: undefined, isDragging: false,
      setNodeRef: () => undefined, setActivatorNodeRef: () => undefined,
      transform: null, node: { current: null },
    }),
    MeasuringStrategy: { Always: 0, BeforeDragging: 1, WhileDragging: 2 },
  };
});

vi.mock('@dnd-kit/sortable', async () => {
  const { createElement, Fragment } = await import('react');
  return {
    SortableContext: (props: { children?: ReactNode }) => createElement(Fragment, null, props.children ?? null),
    useSortable: () => ({
      attributes: {}, listeners: undefined,
      setNodeRef: () => undefined, setActivatorNodeRef: () => undefined,
      setDraggableNodeRef: () => undefined, setDroppableNodeRef: () => undefined,
      transform: null, transition: undefined, isDragging: false, isSorting: false,
      index: 0, newIndex: 0, overIndex: -1, over: null, active: null, items: [],
      containerNodeRect: null, node: { current: null }, rect: null, newNode: { current: null },
    }),
    // arrayMove 保持 dnd-kit 标准语义（重排断言按此口径计算期望序）
    arrayMove: <T,>(list: T[], from: number, to: number): T[] => {
      const copy = list.slice();
      const [moved] = copy.splice(from, 1);
      copy.splice(to, 0, moved as T);
      return copy;
    },
    horizontalListSortingStrategy: () => null,
    verticalListSortingStrategy: () => null,
    sortableKeyboardCoordinates: () => null,
    rectSortingStrategy: () => null,
  };
});

// ————— App 壳装配（tabs 范本口径） —————

const wait = (assertion: () => void, timeout = 10000) => waitFor(assertion, { timeout });
const sleep = (ms: number) => new Promise((resolve_) => setTimeout(resolve_, ms));

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const renderAppAt = async (hash: string) => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: ['system:user:list'], menus: [] });
    }
    if (url.pathname.includes('/system/users')) return envelope({ records: [], total: 0 });
    return envelope(null);
  }));
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

// ————— 页签 / 上下文菜单 / 持久化（tabs 范本口径） —————

const tabbar = (): HTMLElement => {
  const el = document.querySelector('[role="tablist"]');
  if (!el) throw new Error('页签条未渲染');
  return el as HTMLElement;
};

const tabEls = (): HTMLElement[] => Array.from(document.querySelectorAll('.tabbar-tab')) as HTMLElement[];

const tabEl = (path: string): HTMLElement | null =>
  document.querySelector(`.tabbar-tab[data-tab-path="${path}"]`) as HTMLElement | null;

const tabOrder = (): string[] => tabEls().map((el) => el.dataset.tabPath ?? '');

const openTabContextMenu = async (path: string) => {
  await wait(() => {
    expect(document.querySelectorAll('.ant-dropdown:not(.ant-dropdown-hidden)').length).toBe(0);
  });
  const el = tabEl(path);
  if (!el) throw new Error(`页签不存在: ${path}`);
  fireEvent.contextMenu(el);
};

const visibleMenuItems = (): HTMLElement[] =>
  Array.from(document.querySelectorAll('.ant-dropdown:not(.ant-dropdown-hidden) [role="menuitem"]')) as HTMLElement[];

const findVisibleMenuItem = (name: string): HTMLElement | null =>
  visibleMenuItems().find((el) => el.textContent === name) ?? null;

const clickMenuItem = async (name: string) => {
  await wait(() => {
    expect(findVisibleMenuItem(name)).toBeTruthy();
  });
  const item = findVisibleMenuItem(name);
  if (!item) throw new Error(`菜单项未找到: ${name}`);
  fireEvent.click(item);
};

const persistedTabs = (): { tabs: { path: string; pinned?: boolean; permanent?: boolean }[]; activePath: string | null } | null => {
  const raw = window.localStorage.getItem('acme.tabs.v1');
  return raw ? JSON.parse(raw) : null;
};

/** 三页签装配：/（permanent 首位）＋ /apps ＋ /users（激活，权限门禁放行）——104022 范本口径。 */
const seedThreeTabs = async () => {
  await renderAppAt('#/');
  await wait(() => expect(tabEl('/')).toBeTruthy());
  window.location.hash = '#/apps';
  await wait(() => expect(tabEl('/apps')).toBeTruthy());
  window.location.hash = '#/users';
  await wait(() => expect(tabEl('/users')).toBeTruthy());
  expect(tabEls()).toHaveLength(3);
};

// ————— 预览卡定位（104022 范本口径：Portal 挂 body + 隐藏弹层排除 + 快捷操作钮锚定） —————

const previewCardRoot = (leaf: HTMLElement): HTMLElement => {
  let el: HTMLElement = leaf;
  while (el.parentElement && el.parentElement !== document.body) el = el.parentElement;
  return el;
};

const isHiddenSubtree = (el: Element): boolean => {
  let node: Element | null = el;
  while (node && node !== document.body) {
    if ((node as HTMLElement).style?.display === 'none') return true;
    if (Array.from(node.classList).some((c) => /(^|-)hidden$/.test(c))) return true;
    node = node.parentElement;
  }
  return false;
};

const hasActionButton = (root: HTMLElement): boolean =>
  Array.from(root.querySelectorAll('button')).some((b) => {
    const name = (b.getAttribute('aria-label') ?? b.textContent ?? '').trim();
    return name === '刷新' || name === '固定' || name === '取消固定' || name === '关闭';
  });

const findPreviewCard = (label: string): HTMLElement | null => {
  const bar = tabbar();
  const candidates = screen.queryAllByText(label).filter((el) =>
    !bar.contains(el)
    && !el.closest('[role="tabpanel"]')
    && !el.closest('.ant-menu')
    && !el.closest('[role="menuitem"]')
    && !isHiddenSubtree(el));
  for (const leaf of candidates) {
    const root = previewCardRoot(leaf);
    if (hasActionButton(root)) return root;
  }
  return null;
};

const hoverTab = (el: HTMLElement): void => {
  fireEvent.mouseEnter(el);
  fireEvent.pointerEnter(el);
};

// ————— settings 直驱（偏好 store 双写面） —————

type SettingsLike = { schemaVersion?: number; preferences?: Record<string, unknown> };

const currentPrefsLoose = (): Record<string, unknown> => {
  const prefs = (useStore.getState().state.settings as unknown as SettingsLike).preferences;
  expect(prefs, 'settings 应内嵌 preferences 对象').toBeTruthy();
  return prefs as Record<string, unknown>;
};

const setPrefs = (over: Record<string, unknown>): void => {
  useStore.getState().dispatch({
    type: 'UPDATE_SETTINGS',
    patch: { preferences: { ...DEFAULT_PREFERENCES, ...over } as unknown as Settings['preferences'] },
  });
};

const resetSettingsBaseline = (): void => {
  const store = useStore.getState();
  if (typeof store.resetData === 'function') store.resetData();
  store.dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
};

const expectBlobToContain = (snippet: string): void => {
  const raw = window.localStorage.getItem('acme.frontend.ops.v1');
  expect(raw, '偏好应已即时持久化到 acme.frontend.ops.v1').toBeTruthy();
  expect((raw as string).replace(/\s+/g, '')).toContain(snippet);
};

// ————— AC-3 视觉面：风格承载面与 global.css 源块 —————

const STYLE_KEYS = ['line', 'card', 'chrome', 'plain'] as const;

/**
 * 风格承载面宽容扫描（契约④未钉死承载面）：bar/tab 根的 data-*（名含 style/mode/variant/skin）
 * 或类名（tabbar*-<style>）任一承载即合规；返回命中的风格键（line 缺省允许 null）。
 */
const styleDiscriminator = (): string | null => {
  const hosts = Array.from(
    document.querySelectorAll<HTMLElement>('[role="tablist"], [class*="tabbar"]'),
  );
  for (const el of hosts) {
    for (const attr of Array.from(el.attributes ?? [])) {
      const name = attr.name.toLowerCase();
      const value = attr.value.toLowerCase();
      if (name.startsWith('data-') && /style|mode|variant|skin/.test(name)
        && STYLE_KEYS.some((s) => value.includes(s))) {
        return (value.match(new RegExp(STYLE_KEYS.join('|'))))?.[0] ?? null;
      }
    }
    const cls = (el.getAttribute('class') ?? '').toLowerCase();
    const m = new RegExp(`tabbar[^\\s]*-(${STYLE_KEYS.join('|')})\\b`).exec(cls);
    if (m) return m[1] as (typeof STYLE_KEYS)[number];
  }
  return null;
};

// jsdom 环境 import.meta.url 非 file scheme → fileURLToPath 转本地路径再读（104022 范本口径）
const globalCssRaw = readFileSync(resolve(fileURLToPath(import.meta.url), '../../src/styles/global.css'), 'utf8');

const cssBlocks = (): { selector: string; body: string }[] =>
  [...globalCssRaw.matchAll(/([^{}]+)\{([^}]*)\}/g)].map((m) => ({ selector: m[1].trim(), body: m[2].trim() }));

/** 风格 token × 页签上下文的 CSS 规则组（card/chrome/plain 渲染切换的源级锚）。 */
const styleCssRules = (style: string): { selector: string; body: string }[] =>
  cssBlocks().filter((b) => new RegExp(`\\b${style}\\b`, 'i').test(`${b.selector} ${b.body}`)
    && /tab/i.test(`${b.selector} ${b.body}`));

// ————— AC-4 拖拽行为面（mock 捕获 onDragEnd 按钮级驱动） —————

const arrayMove = <T,>(list: T[], from: number, to: number): T[] => {
  const copy = list.slice();
  const [moved] = copy.splice(from, 1);
  copy.splice(to, 0, moved as T);
  return copy;
};

const fireDragEnd = (activeId: string, overId: string | null): void => {
  expect(
    dndRegistry.onDragEnd,
    'TabBar 应注册 DndContext.onDragEnd（拖拽行为面 mock 捕获）',
  ).toBeTruthy();
  dndRegistry.onDragEnd?.({ active: { id: activeId }, over: overId === null ? null : { id: overId } });
};

const expectOrderEverywhere = async (expected: string[], message: string) => {
  await wait(() => {
    expect(useTabsStore.getState().tabs.map((t) => t.path), `${message}（store 顺序）`).toEqual(expected);
  });
  await wait(() => {
    expect(tabOrder(), `${message}（DOM 顺序）`).toEqual(expected);
  });
  expect(persistedTabs()?.tabs.map((t) => t.path), `${message}（acme.tabs.v1 持久化顺序）`).toEqual(expected);
};

// ————— 用例 —————

beforeEach(async () => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  resetSettingsBaseline();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  // 共享环境清理：本文件用例动过 pinned 态 → 归零（permanent 拒固定，跳过）
  for (const tab of useTabsStore.getState().tabs) {
    if (tab.pinned && !tab.permanent) useTabsStore.getState().togglePin(tab.path);
  }
  resetSettingsBaseline();
});

describe('REQ20261122233427 页签四风格渲染切换（AC-3）', () => {
  it('should_switch_style_discriminator_and_persist_when_tab_style_changed', async () => {
    await seedThreeTabs();

    // card：承载面标记 + CSS 源规则 + 持久化
    setPrefs({ tabStyle: 'card' });
    await wait(() => expect(currentPrefsLoose()['tabStyle']).toBe('card'));
    await wait(() => expect(styleDiscriminator(), 'card 风格应在 bar/tab 承载面（data-*/类名）留下标记').toBe('card'));
    expect(styleCssRules('card').length, 'global.css 应存在 card 风格规则（token×页签上下文）').toBeGreaterThan(0);
    expectBlobToContain('"tabStyle":"card"');

    // chrome：梯形隐喻源级锚（clip-path/skew/perspective/polygon/transform 任一）
    setPrefs({ tabStyle: 'chrome' });
    await wait(() => expect(currentPrefsLoose()['tabStyle']).toBe('chrome'));
    await wait(() => expect(styleDiscriminator()).toBe('chrome'));
    const chromeRules = styleCssRules('chrome');
    expect(chromeRules.length, 'global.css 应存在 chrome 风格规则').toBeGreaterThan(0);
    expect(
      chromeRules.some((r) => /clip-path|skew|perspective|polygon|transform/i.test(r.body)),
      `chrome 规则应含梯形隐喻手法（实际：${chromeRules.map((r) => r.selector).join(' | ')}）`,
    ).toBe(true);
    expectBlobToContain('"tabStyle":"chrome"');

    // plain：承载面标记 + CSS 源规则
    setPrefs({ tabStyle: 'plain' });
    await wait(() => expect(currentPrefsLoose()['tabStyle']).toBe('plain'));
    await wait(() => expect(styleDiscriminator()).toBe('plain'));
    expect(styleCssRules('plain').length, 'global.css 应存在 plain 风格规则').toBeGreaterThan(0);
    expectBlobToContain('"tabStyle":"plain"');

    // line（缺省=现行为）：无其他风格标记（null 或 line），且不残留 card/chrome/plain 类
    setPrefs({ tabStyle: 'line' });
    await wait(() => expect(currentPrefsLoose()['tabStyle']).toBe('line'));
    await wait(() => {
      const d = styleDiscriminator();
      expect(d === null || d === 'line', `line 风格不得残留其他风格标记（实际 ${d}）`).toBe(true);
    });
    expectBlobToContain('"tabStyle":"line"');
  });
});

describe('REQ20261122233427 页签行为面零回归（AC-3）', () => {
  it('should_keep_pin_close_preview_behavior_when_card_style_active', async () => {
    // 非缺省风格下 pin/关闭/预览卡行为面零回归（契约④：同一 store/行为面不变）
    setPrefs({ tabStyle: 'card' });
    await seedThreeTabs();
    await wait(() => expect(styleDiscriminator()).toBe('card'));

    // pin：右键 固定 /apps → pinned 持久化
    await openTabContextMenu('/apps');
    await clickMenuItem('固定');
    await wait(() => expect(persistedTabs()?.tabs.find((t) => t.path === '/apps')?.pinned).toBe(true));

    // 关闭：右键 关闭 /users（激活签）→ 移除且激活切邻
    await openTabContextMenu('/users');
    await clickMenuItem('关闭');
    await wait(() => expect(tabEl('/users')).toBeNull());
    await wait(() => expect(tabEl('/apps')?.getAttribute('aria-selected')).toBe('true'));

    // 预览卡：hover 非活动永久签 / → 结构化卡（刷新，无关闭/固定）
    hoverTab(tabEl('/') as HTMLElement);
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('概览');
      expect(found, 'card 风格下 hover 预览卡应浮出').toBeTruthy();
      return found;
    }) as unknown as HTMLElement;
    expect(within(card).getByRole('button', { name: '刷新' })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: '关闭' })).toBeNull();
    expect(within(card).queryByRole('button', { name: '固定' })).toBeNull();
  });
});

describe('REQ20261122233427 拖拽排序与持久化（AC-4）', () => {
  it('should_reorder_tabs_and_persist_when_drag_end_fired', async () => {
    // 拖 /apps → /users 位置：arrayMove 标准语义 [/, /users, /apps]，store/DOM/blob 三面同序
    await seedThreeTabs();
    fireDragEnd('/apps', '/users');
    await expectOrderEverywhere(arrayMove(['/', '/apps', '/users'], 1, 2), '拖拽重排后顺序应一致并持久化');
  });

  it('should_keep_dashboard_in_tabs_when_permanent_tab_dragged', async () => {
    // permanent（仪表盘）不锁拖拽但拖拽后仍在集合内（契约④）
    await seedThreeTabs();
    fireDragEnd('/', '/users');
    const expected = arrayMove(['/', '/apps', '/users'], 0, 2);
    await expectOrderEverywhere(expected, '拖拽 permanent 后顺序应更新');
    await wait(() => expect(tabEl('/'), '仪表盘 permanent 拖拽后必须仍在集合内').toBeTruthy());
    expect(persistedTabs()?.tabs.some((t) => t.path === '/' && t.permanent), '持久化中 permanent 标记不得丢').toBe(true);
  });

  it('should_keep_dashboard_in_tabs_when_other_tab_dragged_over_it', async () => {
    // 其他签拖过 permanent：顺序更新，仪表盘不被拖出集合
    await seedThreeTabs();
    fireDragEnd('/users', '/');
    const expected = arrayMove(['/', '/apps', '/users'], 2, 0);
    await expectOrderEverywhere(expected, '拖拽跨 permanent 后顺序应更新');
    await wait(() => expect(tabEl('/'), '仪表盘 permanent 不得被拖出集合').toBeTruthy());
    expect(tabEls()).toHaveLength(3);
  });
});
