/**
 * REQ-20261119-063129：web 多页签＋页面缓存＋全局命令面板（App 装配级契约用例）。
 *
 * 覆盖（按 AC 回填用例名）：
 * - AC-1 多页签打开/激活/切回状态保留（表单输入断言）；
 * - AC-2 页签右键菜单语义（刷新=重挂/关闭/固定·pinned 不可关/关闭其他/关闭右侧/关闭全部）；
 * - AC-3 页签持久化还原（含 pinned；损坏回退）；
 * - AC-5 命令面板三节/键盘导航/动作执行（页面=开页签；锁屏动作）；
 * - AC-6 逐 tab PermissionGate（无权限页签内容 403；BUTTON 节点不入页签）；
 * - AC-7 登出清空页签并移除 acme.tabs.v1；
 * - 迭代轮次 1（契约⑨⑩）：首页常驻 dashboard permanent（关闭/固定拒绝、closeOthers/closeAll 保留、
 *   登出清存储后重建）、pinned 语义保留（非首页签）、TabBar 紧凑化视觉锚（36px/底边活动线/16px 关闭钮）。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-nav.test.tsx：seedSession + useMeStore 播种 +
 * HashRouter 上下文 + <AntApp> 包裹 + matchMedia/ResizeObserver stub + testTimeout ≥15000ms。
 * 页签 label 断言一律 within(tabbar) 收敛（页签 label 与侧边导航/页面标题同源，全树查询会多命中）。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { findPageRoute } from '../src/routes';
import { useTabsStore } from '../src/store/tabsStore';
import { useAuthStore, useLockStore, useMeStore, useNotifyStore } from '@acme/core';

vi.setConfig({ testTimeout: 90000 }); // delta-r4：并行全量下 30s 饥饿超时（串行 18/18 绿），对齐显式超时族

/**
 * 全套件并行负载下默认 1s 查找窗口偏紧（nav.test 同款放宽口径）：统一 10s 上限的 waitFor。
 */
const wait = (assertion: () => void, timeout = 10000) => waitFor(assertion, { timeout });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: 'DIRECTORY' | 'MENU' | 'BUTTON';
  path: string | null; component: string | null; icon: string | null; sortOrder: number;
  visible: boolean; permissionCode: string | null; status: 'ENABLED' | 'DISABLED';
  children: MenuNode[]; version: string;
};

const menuNode = (over: Partial<MenuNode> = {}): MenuNode => ({
  id: '1', parentId: '0', menuName: '', menuType: 'MENU', path: '/', component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

const stubBrowserGaps = () => {
  // min-width 断点置 true：App 按桌面布局渲染（nav.test 装配口径）
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

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/**
 * App 整棵装配（nav.test 口径）：播种会话与 me 画像（menus 缺省空数组=静态 NAV 回退），
 * 伴随请求统一回空信封；restEnvelopes 允许用例补特定端点（形如 pathname 片段 → 响应工厂）。
 */
const renderAppAt = async (
  hash: string,
  options: { permissions?: string[]; menus?: MenuNode[]; routes?: Record<string, (r: { url: URL; method: string }) => Response | undefined> } = {},
) => {
  seedSession();
  const { permissions = [], menus = [], routes = {} } = options;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions, menus });
    }
    for (const [fragment, respond] of Object.entries(routes)) {
      if (url.pathname.includes(fragment)) {
        const custom = respond({ url, method: _init?.method ?? 'GET' });
        if (custom) return custom;
      }
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const tabbar = (): HTMLElement => {
  const el = document.querySelector('[role="tablist"]');
  if (!el) throw new Error('页签条未渲染');
  return el as HTMLElement;
};

const tabEls = (): HTMLElement[] => Array.from(document.querySelectorAll('.tabbar-tab')) as HTMLElement[];

const tabEl = (path: string): HTMLElement | null =>
  document.querySelector(`.tabbar-tab[data-tab-path="${path}"]`) as HTMLElement | null;

const tabPanel = (path: string): HTMLElement | null =>
  document.querySelector(`[data-tab-path="${path}"][role="tabpanel"]`) as HTMLElement | null;

/** 右键打开页签上下文菜单（antd Dropdown contextMenu 触发；先等上一份菜单关闭，防隐藏 DOM 叠加）。 */
const openTabContextMenu = async (path: string) => {
  await wait(() => {
    expect(document.querySelectorAll('.ant-dropdown:not(.ant-dropdown-hidden)').length).toBe(0);
  });
  const el = tabEl(path);
  if (!el) throw new Error(`页签不存在: ${path}`);
  fireEvent.contextMenu(el);
};

/** 仅取可见下拉菜单项（antd 关闭菜单保留隐藏 DOM，全量查询会命中陈旧副本）。 */
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

/** 按用户菜单触发登出（hover 用户菜单 → 点击退出登录）。 */
const logoutViaUserMenu = async () => {
  fireEvent.mouseEnter(screen.getByRole('button', { name: '打开用户菜单' }));
  await clickMenuItem('退出登录');
};

const persistedTabs = (): { tabs: { path: string; pinned?: boolean; permanent?: boolean }[]; activePath: string | null } | null => {
  const raw = window.localStorage.getItem('acme.tabs.v1');
  return raw ? JSON.parse(raw) : null;
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
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
});

// ————— AC-1：多页签打开/激活/切回状态保留 —————
describe('REQ20261119063129 AC-1 多页签打开与切换', () => {
  it('should_open_tab_and_activate_when_navigating_between_pages', async () => {
    await renderAppAt('#/');

    // 初始 tabs 仅含当前 path（契约⑦语义）：单签且激活；首页签 permanent（契约⑩，无关闭/固定钮）
    await wait(() => {
      expect(tabEls()).toHaveLength(1);
      expect(tabEls()[0]?.dataset.tabPath).toBe('/');
      expect(tabEls()[0]?.getAttribute('aria-selected')).toBe('true');
      expect(tabEls()[0]?.querySelector('[aria-label="关闭"]')).toBeNull();
      expect(tabEls()[0]?.querySelector('[aria-label="取消固定"]')).toBeNull();
    });

    window.location.hash = '#/apps';
    await wait(() => {
      expect(tabEl('/apps')).toBeTruthy();
      expect(tabEl('/apps')?.getAttribute('aria-selected')).toBe('true');
    });
    // 旧页签保留（keepalive 容器仍在，非激活 display:none）
    expect(tabEl('/')).toBeTruthy();
    expect(tabEl('/')?.getAttribute('aria-selected')).toBe('false');
    expect(tabPanel('/')?.style.display).toBe('none');
    expect(tabPanel('/apps')?.style.display).toBe('');
    // label 取 nav.items.* 映射（静态 NAV 回退场景）
    expect(within(tabbar()).getByText('概览')).toBeTruthy();
    expect(within(tabbar()).getByText('应用结构')).toBeTruthy();
  });

  it('should_keep_form_input_when_switching_tabs_and_back', async () => {
    await renderAppAt('#/op-exceptions', {
      permissions: ['infra:exception:list', 'infra:audit:list'],
      routes: {
        '/op-exceptions': () => envelope({ records: [], total: 0 }),
        '/audit': () => envelope({ records: [], total: 0 }),
      },
    });
    const input = await screen.findByPlaceholderText('服务名', {}, { timeout: 10000 });
    fireEvent.change(input, { target: { value: 'platform-infra-service' } });
    expect(input).toHaveProperty('value', 'platform-infra-service');

    // 切到审计页签再切回：keepalive 下页面不卸载，输入状态保留（AC-1 表单输入断言）
    window.location.hash = '#/audit';
    await wait(() => {
      expect(tabEl('/audit')?.getAttribute('aria-selected')).toBe('true');
    });
    window.location.hash = '#/op-exceptions';
    await wait(() => {
      expect(tabEl('/op-exceptions')?.getAttribute('aria-selected')).toBe('true');
    });
    const restored = screen.getByPlaceholderText('服务名');
    expect(restored).toBe(input); // 同一 DOM 节点（未重挂）
    expect(restored).toHaveProperty('value', 'platform-infra-service');
  });
});

// ————— AC-2：页签右键菜单语义 —————
describe('REQ20261119063129 AC-2 页签右键菜单', () => {
  const renderThreeTabs = async () => {
    const utils = await renderAppAt('#/', {
      permissions: ['system:user:list'],
      routes: { '/system/users': () => envelope({ records: [], total: 0 }) },
    });
    await wait(() => expect(tabEl('/')).toBeTruthy());
    window.location.hash = '#/apps';
    await wait(() => expect(tabEl('/apps')).toBeTruthy());
    window.location.hash = '#/users';
    await wait(() => expect(tabEl('/users')).toBeTruthy());
    expect(tabEls()).toHaveLength(3);
    return utils;
  };

  it('should_refresh_page_when_context_menu_refresh_used', async () => {
    const { fetchMock } = await renderThreeTabs();
    const usersGets = () => (fetchMock.mock.calls as unknown as [RequestInfo | URL][])
      .filter(([input]) => new URL(String(input), 'http://localhost').pathname.includes('/system/users')).length;
    // 页签创建先于 lazy chunk 挂载拉取：先等首拉发生再记基线
    await wait(() => {
      expect(usersGets()).toBeGreaterThanOrEqual(1);
    });

    // 右键用户页签 → 刷新 = refreshKey 重挂该页（重挂触发重新拉取）
    const before = usersGets();
    await openTabContextMenu('/users');
    await clickMenuItem('刷新');
    await wait(() => {
      expect(usersGets()).toBeGreaterThan(before);
    });
  });

  it('should_close_tab_and_switch_to_neighbor_when_active_tab_closed', async () => {
    await renderThreeTabs();

    // 关闭非激活页签：仅移除该签，激活态不变
    await openTabContextMenu('/apps');
    await clickMenuItem('关闭');
    await wait(() => expect(tabEl('/apps')).toBeNull());
    expect(tabEl('/')).toBeTruthy();
    expect(tabEl('/users')?.getAttribute('aria-selected')).toBe('true');

    // 关闭激活页签：切相邻（优先右侧，无右侧取左侧），hash 对齐
    await openTabContextMenu('/users');
    await clickMenuItem('关闭');
    await wait(() => expect(window.location.hash).toBe('#/'));
    expect(tabEl('/users')).toBeNull();
    expect(tabEl('/')?.getAttribute('aria-selected')).toBe('true');
  });

  it('should_pin_tab_prevent_close_and_unpin_when_pin_used', async () => {
    await renderThreeTabs();

    // 固定非首页签（pinned 语义保留，契约⑩）：pinned 紧随首页常驻签（permanent 首位不动）、
    // 关闭钮替换为取消固定钮
    await openTabContextMenu('/users');
    await clickMenuItem('固定');
    await wait(() => {
      expect(tabEl('/users')?.querySelector('[aria-label="取消固定"]')).toBeTruthy();
    });
    expect(tabEls().map((el) => el.dataset.tabPath)).toEqual(['/', '/users', '/apps']); // permanent 首位 + pinned 前置

    // pinned 不可关：上下文菜单「关闭」禁用，点击不生效
    await openTabContextMenu('/users');
    await wait(() => {
      const closeItem = findVisibleMenuItem('关闭');
      expect(closeItem).toBeTruthy();
      expect(closeItem?.classList.contains('ant-dropdown-menu-item-disabled')).toBe(true);
    });
    fireEvent.click(findVisibleMenuItem('关闭')!);
    await wait(() => {
      expect(tabEl('/users')).toBeTruthy();
      expect(tabEls()).toHaveLength(3); // pinned 未被关闭
    });

    // 取消固定：恢复关闭钮
    fireEvent.click(tabEl('/users')!.querySelector('[aria-label="取消固定"]')!);
    await wait(() => {
      expect(tabEl('/users')?.querySelector('[aria-label="关闭"]')).toBeTruthy();
    });
  });

  it('should_close_others_right_and_all_when_menu_used', async () => {
    await renderThreeTabs();

    // 关闭其他：保留点击签＋首页常驻签（契约⑩ permanent 保留，原 pinned 语义不变）
    await openTabContextMenu('/apps');
    await clickMenuItem('关闭其他');
    await wait(() => {
      expect(tabEls()).toHaveLength(2);
      expect(tabEl('/apps')?.getAttribute('aria-selected')).toBe('true');
      expect(tabEl('/')).toBeTruthy();
    });

    // 重建三签后关闭右侧：保留点击签及其左侧（含首页常驻签）
    window.location.hash = '#/';
    await wait(() => expect(tabEl('/')?.getAttribute('aria-selected')).toBe('true'));
    window.location.hash = '#/users';
    await wait(() => expect(tabEl('/users')).toBeTruthy());
    await openTabContextMenu('/apps');
    await clickMenuItem('关闭右侧');
    await wait(() => {
      expect(tabEls()).toHaveLength(2);
      expect(tabEl('/')).toBeTruthy();
      expect(tabEl('/apps')).toBeTruthy();
      expect(tabEl('/users')).toBeNull();
    });

    // 关闭全部：仅首页常驻签保留（契约⑩），激活态落回首页，内容区不留白
    await openTabContextMenu('/apps');
    await clickMenuItem('关闭全部');
    await wait(() => {
      expect(tabEls()).toHaveLength(1);
      expect(tabEl('/')?.getAttribute('aria-selected')).toBe('true');
    });
    expect(findPageRoute('/')).toBeTruthy();
  });
});

// ————— AC-3：页签持久化还原（含 pinned；损坏回退） —————
describe('REQ20261119063129 AC-3 页签持久化', () => {
  it('should_restore_tabs_with_pinned_when_app_remounts', async () => {
    await renderAppAt('#/', { permissions: ['system:user:list'] });
    await wait(() => expect(tabEl('/')).toBeTruthy());
    window.location.hash = '#/apps';
    await wait(() => expect(tabEl('/apps')).toBeTruthy());

    // 固定 /apps（store 动作语义与右键菜单一致；首页常驻签 permanent 首位不动）
    useTabsStore.getState().togglePin('/apps');
    await wait(() => {
      const persisted = persistedTabs();
      expect(persisted?.tabs.map((tab) => tab.path)).toEqual(['/', '/apps']);
      expect(persisted?.tabs[0]?.permanent).toBe(true); // 首页常驻签随页签持久化
      expect(persisted?.tabs[1]?.pinned).toBe(true);
    });

    // 模拟刷新：卸载后按持久化还原；激活页对齐当前 hash
    cleanup();
    window.location.hash = '#/';
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
    await wait(() => {
      expect(tabEls().map((el) => el.dataset.tabPath)).toEqual(['/', '/apps']);
    });
    expect(tabEl('/')?.getAttribute('aria-selected')).toBe('true'); // 激活页对齐当前 hash（AC-3）
    expect(tabEl('/apps')?.querySelector('[aria-label="取消固定"]')).toBeTruthy(); // pinned 还原
    expect(tabEl('/')?.querySelector('[aria-label="关闭"]')).toBeNull(); // permanent 还原（无关闭钮）
  });

  it('should_fallback_to_seed_and_current_path_when_storage_corrupted', async () => {
    window.localStorage.setItem('acme.tabs.v1', '{{not-json');
    await renderAppAt('#/apps', { permissions: [] });

    // 损坏回退（契约②）＋首页常驻（契约⑩）：dashboard permanent 种子＋当前 path 打开为页签
    await wait(() => {
      expect(tabEls().map((el) => el.dataset.tabPath)).toEqual(['/', '/apps']);
      expect(tabEl('/apps')?.getAttribute('aria-selected')).toBe('true');
      expect(tabEl('/')?.querySelector('[aria-label="关闭"]')).toBeNull();
    });
  });
});

// ————— AC-5：命令面板（三节/键盘/动作） —————
describe('REQ20261119063129 AC-5 命令面板', () => {
  it('should_open_palette_with_three_sections_when_ctrl_k_pressed', async () => {
    await renderAppAt('#/', { permissions: [] });

    fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
    const dialog = await screen.findByRole('dialog', {}, { timeout: 10000 });
    expect(within(dialog).getByPlaceholderText('搜索导航菜单')).toBeTruthy();
    expect(within(dialog).getByText('页面')).toBeTruthy();
    expect(within(dialog).getByText('页签')).toBeTruthy();
    expect(within(dialog).getByText('快捷操作')).toBeTruthy();
  });

  it('should_navigate_and_open_tab_when_page_command_executed_by_keyboard', async () => {
    await renderAppAt('#/', { permissions: [] });
    await wait(() => expect(tabEl('/')).toBeTruthy());

    fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
    const dialog = await screen.findByRole('dialog', {}, { timeout: 10000 });
    const input = within(dialog).getByPlaceholderText('搜索导航菜单') as HTMLInputElement;

    // 键盘导航：初始选中第一节首项，ArrowDown 下移
    expect(dialog.querySelector('.command-palette-item[data-active="true"]')?.textContent).toContain('概览');
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(dialog.querySelector('.command-palette-item[data-active="true"]')?.textContent).toContain('发布单');

    // 过滤 + Enter 执行：页面命令 =「打开为页签」（按 path 提示过滤，避免命中「新建发布单」动作项）
    fireEvent.change(input, { target: { value: '/releases' } });
    await wait(() => {
      expect(within(dialog).getByText('发布单')).toBeTruthy();
    });
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => {
      expect(window.location.hash).toBe('#/releases');
      expect(tabEl('/releases')).toBeTruthy();
    });
    await wait(() => {
      expect(screen.queryByRole('dialog')).toBeNull(); // 执行后关闭
    });
  });

  it('should_close_palette_when_escape_pressed', async () => {
    await renderAppAt('#/', { permissions: [] });

    fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
    await screen.findByRole('dialog', {}, { timeout: 10000 });
    const input = screen.getByPlaceholderText('搜索导航菜单');
    // Esc 关闭经 antd Modal keyboard 通道（rc-dialog 按 keyCode===27 判定）
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 27 });
    await wait(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('should_lock_screen_when_quick_action_executed', async () => {
    await renderAppAt('#/', { permissions: [] });

    fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
    const dialog = await screen.findByRole('dialog', {}, { timeout: 10000 });
    const input = within(dialog).getByPlaceholderText('搜索导航菜单') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '锁定' } });
    await wait(() => {
      expect(within(dialog).getByText('锁定屏幕')).toBeTruthy();
    });
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => {
      expect(useLockStore.getState().locked).toBe(true);
    });
    useLockStore.setState({ locked: false, passcode: '' }); // 防锁屏覆盖层跨用例残留
  });
});

// ————— AC-6：逐 tab PermissionGate + BUTTON 节点不入页签 —————
describe('REQ20261119063129 AC-6 逐页签权限门禁', () => {
  it('should_render_permission_denial_inside_tab_when_unauthorized_page_opened', async () => {
    // 动态菜单含 BUTTON 节点：仅根 MENU '/' 可导航；BUTTON（无 path）不进页签（nav.ts 剔除）
    const menus: MenuNode[] = [
      menuNode({
        id: '1', menuName: '首页面板', menuType: 'MENU', path: '/',
        children: [menuNode({ id: '11', parentId: '1', menuName: '用户新建按钮', menuType: 'BUTTON', path: null, permissionCode: 'system:user:create' })],
      }),
    ];
    await renderAppAt('#/', { permissions: [], menus });
    await wait(() => {
      expect(tabEls()).toHaveLength(1);
      // 页签 label 优先动态菜单 menuName（契约②优先级）
      expect(within(tabbar()).getByText('首页面板')).toBeTruthy();
    });

    // 直达无权限路由：页签照常打开，PermissionGate 逐 tab 生效（403 页签内容）
    window.location.hash = '#/users';
    await wait(() => {
      expect(tabEl('/users')).toBeTruthy();
    });
    const deniedPanel = tabPanel('/users');
    expect(deniedPanel?.textContent).toContain('无访问权限');
    expect(deniedPanel?.textContent).toContain('system:user:list');
    // 概览签不受影响（逐 tab 隔离）
    expect(tabPanel('/')?.dataset.tabActive).toBe('false');
    expect(tabPanel('/users')?.dataset.tabActive).toBe('true');
    // BUTTON 节点未产生页签
    expect(tabEls()).toHaveLength(2);
  });
});

// ————— AC-7：登出清空页签并移除存储 —————
describe('REQ20261119063129 AC-7 登出页签清理', () => {
  it('should_clear_tabs_and_remove_storage_when_logout', async () => {
    const { fetchMock } = await renderAppAt('#/', {
      permissions: ['system:user:list'],
      routes: { '/system/users': () => envelope({ records: [], total: 0 }) },
    });
    await wait(() => expect(tabEl('/')).toBeTruthy());
    window.location.hash = '#/users';
    await wait(() => expect(tabEl('/users')).toBeTruthy());
    expect(persistedTabs()?.tabs.length).toBe(2);

    await logoutViaUserMenu();

    await wait(() => {
      expect(window.location.hash).toBe('#/login');
    });
    await wait(() => {
      // 契约⑩：登出清空页签后重建首页常驻签（种子不入盘）；acme.tabs.v1 存储键仍被清理（AC-7）
      const tabs = useTabsStore.getState().tabs;
      expect(tabs).toHaveLength(1);
      expect(tabs[0]?.path).toBe('/');
      expect(tabs[0]?.permanent).toBe(true);
      expect(window.localStorage.getItem('acme.tabs.v1')).toBeNull();
    });
    // 登出请求幂等成功（信封桩）：DELETE /admin/v1/auth/tokens/current
    const logoutCalls = (fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit | undefined][])
      .filter(([input, init]) => new URL(String(input), 'http://localhost').pathname.endsWith('/tokens/current')
        && (init?.method ?? 'GET').toUpperCase() === 'DELETE');
    expect(logoutCalls.length).toBeGreaterThanOrEqual(1);
  });
});

// ————— 迭代轮次 1（契约⑩）：首页常驻 dashboard permanent —————
describe('REQ20261119063129 迭代1 首页常驻 dashboard permanent', () => {
  it('should_reject_close_and_pin_on_permanent_dashboard_tab', async () => {
    await renderAppAt('#/', { permissions: [] });
    await wait(() => expect(tabEl('/')).toBeTruthy());

    // store 层：closeTab/togglePin 对 permanent 拒绝（无副作用，契约⑩）
    expect(useTabsStore.getState().closeTab('/')).toBe('/');
    expect(useTabsStore.getState().tabs.map((tab) => tab.path)).toEqual(['/']);
    expect(useTabsStore.getState().tabs[0]?.permanent).toBe(true);
    useTabsStore.getState().togglePin('/');
    expect(useTabsStore.getState().tabs[0]?.pinned).toBeUndefined();
    expect(useTabsStore.getState().tabs[0]?.permanent).toBe(true);

    // UI 层：首页签无关闭钮/取消固定钮；右键菜单「关闭」「固定」禁用
    expect(tabEl('/')?.querySelector('[aria-label="关闭"]')).toBeNull();
    expect(tabEl('/')?.querySelector('[aria-label="取消固定"]')).toBeNull();
    await openTabContextMenu('/');
    await wait(() => {
      expect(findVisibleMenuItem('关闭')?.classList.contains('ant-dropdown-menu-item-disabled')).toBe(true);
      expect(findVisibleMenuItem('固定')?.classList.contains('ant-dropdown-menu-item-disabled')).toBe(true);
    });
  });

  it('should_seed_dashboard_permanent_first_when_session_restored', async () => {
    // 登入 hydrate（还原旧存储）→ seed 归一化：dashboard permanent 挪至首位、pinned 残留清除（契约⑩）
    window.localStorage.setItem('acme.tabs.v1', JSON.stringify({
      tabs: [{ path: '/users' }, { path: '/', pinned: true }],
      activePath: '/users',
    }));
    await renderAppAt('#/users', { permissions: ['system:user:list'] });
    await wait(() => {
      const tabs = useTabsStore.getState().tabs;
      expect(tabs.map((tab) => tab.path)).toEqual(['/', '/users']);
      expect(tabs[0]?.permanent).toBe(true);
      expect(tabs[0]?.pinned).toBeUndefined();
      expect(tabEl('/')?.querySelector('[aria-label="关闭"]')).toBeNull();
    });
  });
});

// ————— 迭代轮次 1（契约⑨）：TabBar 紧凑化视觉锚（global.css 源级锁定，范本=global-css.test.ts） —————
describe('REQ20261119063129 迭代1 TabBar 紧凑化视觉锚', () => {
  // jsdom 环境的 URL 构造器产出的实例不被 node:fs 接受 → 经 fileURLToPath 转本地路径再读
  const css = readFileSync(resolve(fileURLToPath(import.meta.url), '../../src/styles/global.css'), 'utf8');

  /** 单行规则提取：global.css 页签条段为「selector { body }」单行格式，按选择器精确取规则体。 */
  const cssRule = (selector: string): string => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
    expect(match, `global.css 缺少规则: ${selector}`).toBeTruthy();
    return match![1];
  };

  it('should_lock_compact_tabbar_anchors_when_css_loaded', () => {
    // 条高 36px＋横向溢出滚动且滚动条隐藏
    const bar = cssRule('.tabbar');
    expect(bar).toContain('height: 36px');
    expect(bar).toContain('overflow-x: auto');
    expect(bar).toContain('scrollbar-width: none');
    expect(cssRule('.tabbar::-webkit-scrollbar')).toContain('display: none');

    // tab 内边距收紧（水平 10px）＋label 13px
    const tab = cssRule('.tabbar-tab');
    expect(tab).toContain('padding: 0 10px');
    expect(tab).toContain('font-size: 13px');

    // 活动指示=底边 2px accent 线（替代宽 pill 填充）
    const activeLine = cssRule('.tabbar-tab-active::after');
    expect(activeLine).toContain('height: 2px');
    expect(activeLine).toContain('background: var(--accent)');

    // 关闭钮 16px；非活动签 hover 才显（活动签/键盘聚焦常显）；::after 扩展命中区保留
    const close = cssRule('.tabbar-close, .tabbar-pin');
    expect(close).toContain('width: 16px');
    expect(close).toContain('height: 16px');
    const hitArea = cssRule('.tabbar-close::after, .tabbar-pin::after');
    expect(hitArea).toContain("content: ''");
    expect(hitArea).toContain('position: absolute');
    expect(cssRule('.tabbar-close')).toContain('opacity: 0');
    expect(cssRule('.tabbar-tab:hover .tabbar-close, .tabbar-tab:focus-within .tabbar-close, .tabbar-tab-active .tabbar-close'))
      .toContain('opacity: 1');
  });

  it('should_use_token_layer_only_when_tabbar_colors_defined', () => {
    // 色值一律走令牌变量层（红线）：页签条段不得出现硬编码色值，hover 提亮走 surface-2（elevated）
    const block = css.slice(css.indexOf('/* 页签条'), css.indexOf('/* 命令面板'));
    expect(block.length).toBeGreaterThan(0);
    expect(block).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(block).toContain('var(--accent)');
    expect(block).toContain('var(--surface-2)');
    expect(block).toContain('var(--border)');
  });
});
