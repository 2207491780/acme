/**
 * REQ-20261114-120103 AC-3：ErrorBoundary——页面级局部边界（兜底 UI + 重试恢复）与
 * App 级最终防线（未捕获错误不白屏）。
 *
 * 装配照既有范本：
 *   - web/tests/REQ20261114120102-notificationBell.test.tsx（App 整棵装配：seedSession +
 *     endsWith('/me') 精确收敛 + HashRouter + MENUS fixture + <AntApp> 包裹）
 *   - web/tests/REQ20261114120101-nav.test.tsx（菜单树 fixture 口径）
 *
 * 【表面假定（REQ20261114120103 套件内连续编号）】
 *   A-12 ErrorBoundary 表面：web/src/components/ErrorBoundary.tsx 命名导出 ErrorBoundary
 *       （对齐 components/NotificationBell 命名导出惯例）；兜底 UI 含至少一个重试按钮，
 *       重试=原地重渲染子树（点击后子组件恢复内容即判成功，多按钮则逐个试探直至恢复）；
 *       兜底文案/结构未契约化 → 兜底断言=非白屏（内容非空）+ 重试按钮存在。
 *   A-12b App 级兜底最终防线：App 装配含 App 级 ErrorBoundary；兜底文案未契约化 →
 *       断言=路由页抛错后渲染树未整体卸载（body 内容非空、render 不抛），
 *       并以健康路由控制组（'#/' 菜单树正常渲染）对照排除「边界误伤健康页」。
 *   A-13 路由面：hash 路由，'#/' 渲染 Dashboard、'#/users' 渲染 UsersPage
 *       （me.menus fixture path + nav.test 口径）；UsersPage 经 vi.mock 注入渲染抛错组件。
 *   A-12c Thrower 无界抛错+模块级开关（round2 delta，契约确认记录⑧平台事实）：React 18 dev
 *       构建的内置重试会消化「前 N 次抛错」（一次渲染尝试≈2 次组件调用，有限次数抛错的错误
 *       永远到不了 ErrorBoundary——round1 的 `<=2` 贯穿式即被第 2 次尝试消化，DOM 实证
 *       「已恢复内容」直出）。App 级 2 例能真实经过边界，正因 UsersPage mock 每次渲染必抛
 *       （无界抛错在重试耗尽后冒给边界）。故页面级 Thrower=全程抛错 + 模块级开关 throwEnabled：
 *       用例 1 保持抛错使错误冒给页面级边界；用例 2 兜底出现后置 false（模拟上游修复）
 *       再点击重试（复位边界）→ 子内容恢复。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { ErrorBoundary } from '../src/components/ErrorBoundary';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// App 级用例注入抛错页（A-13）：UsersPage 渲染即抛（factory 内联，不可引用外部变量）
vi.mock('../src/pages/UsersPage', () => ({
  UsersPage: () => {
    throw new Error('boom-users-page');
  },
}));

const RECOVERED_TEXT = '已恢复内容';

// 页面级 Thrower（无界抛错+模块级开关，A-12c / 契约确认记录⑧）：React 18 dev 内置重试会消化
// 有限次数的抛错——必须「每次渲染都抛」错误才会在重试耗尽后真正冒给页面级 ErrorBoundary；
// 用例 2 以 throwEnabled=false 模拟上游修复，点击重试（复位边界）后子内容恢复。
let throwEnabled = true;
function Thrower() {
  if (throwEnabled) {
    throw new Error('render-boom-unbounded');
  }
  return <div>{RECOVERED_TEXT}</div>;
}

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— App 装配 fixture（nav.test 口径） —————
type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: 'DIRECTORY' | 'MENU';
  path: string; component: string | null; icon: null | string; sortOrder: number;
  visible: boolean; permissionCode: string | null; status: 'ENABLED' | 'DISABLED';
  children: MenuNode[]; version: string;
};

const menuNode = (over: Partial<MenuNode> = {}): MenuNode => ({
  id: '1', parentId: '0', menuName: '', menuType: 'MENU', path: '/', component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

const MENUS: MenuNode[] = [
  menuNode({ id: '1', menuName: '首页概览', menuType: 'MENU', path: '/dashboard', sortOrder: 1 }),
  menuNode({
    id: '2', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', sortOrder: 2,
    children: [
      menuNode({ id: '21', parentId: '2', menuName: '用户中心', path: '/users', permissionCode: 'system:user:list', sortOrder: 1 }),
      menuNode({ id: '22', parentId: '2', menuName: '租户管理', path: '/tenants', permissionCode: 'system:tenant:list', sortOrder: 2 }),
    ],
  }),
];

const HELD_PERMS = ['system:user:list', 'system:tenant:list'];

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const stubBrowserGaps = () => {
  // min-width 断点置 true：App 整棵装配按桌面布局渲染（nav.test 装配口径）
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

/** App 整棵装配（notificationBell renderAppWithNotify 口径）。 */
const renderAppAt = async (hash: string) => {
  seedSession();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    // endsWith('/me') 精确收敛（防 unread-count 类子串劫持，120102 裁决口径）
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: HELD_PERMS, menus: MENUS });
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  }));
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

const clickRetryUntilRecovered = () => {
  const buttons = screen.getAllByRole('button');
  for (const btn of buttons) {
    fireEvent.click(btn);
    if (screen.queryByText(RECOVERED_TEXT)) return;
  }
  throw new Error('A-12：兜底按钮点击后未恢复子内容（重试语义=原地重渲染子树的假定未命中）');
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
  throwEnabled = true; // 每例复位抛错开关（A-12c，round2 delta）
  // 抛错场景 React 必经 console.error：静音（afterEach restoreAllMocks 还原）
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  // 定时器卫生顺序（120102 wsClient 教训）：先还原 mock 再做其余清理
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-3：页面级局部边界 —————
describe('REQ20261114120103 ErrorBoundary 页面级（AC-3）', () => {
  it('should_render_fallback_ui_with_retry_when_child_throws_on_mount', async () => {
    const { container } = render(
      <AntApp><ErrorBoundary><Thrower /></ErrorBoundary></AntApp>,
    );

    // 兜底接管：无界抛错在 dev 重试耗尽后冒给页面级边界（A-12c）——
    // 子内容未出现、渲染树非白屏、重试按钮存在
    expect(screen.queryByText(RECOVERED_TEXT)).toBeNull();
    await waitFor(() => {
      expect(container.textContent!.trim().length).toBeGreaterThan(0);
    });
    expect(screen.getAllByRole('button').length).toBeGreaterThanOrEqual(1);
  });

  it('should_recover_child_when_retry_clicked', async () => {
    render(<AntApp><ErrorBoundary><Thrower /></ErrorBoundary></AntApp>);
    await waitFor(() => {
      expect(screen.getAllByRole('button').length).toBeGreaterThanOrEqual(1);
    });
    expect(screen.queryByText(RECOVERED_TEXT)).toBeNull();

    // 模拟上游修复（A-12c，round2 delta）：停止抛错后再点重试，复位边界的重渲染即成功
    throwEnabled = false;
    // 重试（A-12：原地重渲染子树；多按钮逐个试探直至恢复）
    clickRetryUntilRecovered();
    expect(screen.getByText(RECOVERED_TEXT)).toBeTruthy();
  });
});

// ————— AC-3：App 级最终防线 —————
describe('REQ20261114120103 ErrorBoundary App 级（AC-3）', () => {
  it('should_render_healthy_route_when_no_error_occurs', async () => {
    // 控制组：健康路由（'#/'）正常渲染菜单树——排除 App 级边界误伤健康页（A-12b）
    await renderAppAt('#/');
    expect(await screen.findByText('首页概览')).toBeTruthy();
    expect(screen.getByText('系统管理')).toBeTruthy();
  });

  it('should_keep_tree_rendered_when_routed_page_throws', async () => {
    // '#/users' 渲染被 mock 为渲染即抛的 UsersPage：App 级兜底接管，渲染树不整体卸载（不白屏）
    await renderAppAt('#/users');

    await waitFor(() => {
      expect(document.body.textContent!.trim().length).toBeGreaterThan(50);
    });
    expect(document.body.children.length).toBeGreaterThan(0);
    // 未被 401 语义劫持跳登录（错误边界接管而非会话清理）
    expect(window.location.hash).toBe('#/users');
  });
});
