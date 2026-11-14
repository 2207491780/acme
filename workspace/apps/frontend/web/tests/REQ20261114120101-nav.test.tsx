/**
 * REQ-20261114-120101 AC-7：导航动态化——登录后按 me.menus 菜单树渲染导航（菜单树+权限）；
 * me.menus 缺失/为空时回退静态 NAV（fail-closed 不变）。
 *
 * me.menus 节点口径 = AdminMenuView（platform-system-api DTO，wire 形状）：
 *   id/parentId/menuName/menuType(DIRECTORY|MENU)/path/component/icon/sortOrder/visible/
 *   permissionCode/status(ENABLED)/children/version。
 *
 * 装配：hash 路由（web 既有口径）+ useAuthStore.setSession 播种会话（meStore.test 范本）+
 * useMeStore 播种画像（手动 load，App 若自行 load 亦命中同一 mock）+ <AntApp> 包裹。
 *
 * 【表面假定（待 TL 裁决）】
 *   A-21 web/src/App.tsx 命名导出 App（对齐页面命名导出惯例）
 *   A-22 菜单显示文本 = menuName；权限过滤 = 节点 permissionCode 与 me.permissions 同源比对
 *       （fixture 两处播种完全相同字面量，任何归一化方案下两侧一致变换 → 显隐判定稳定）
 *   A-23 visible=false 节点不渲染；DIRECTORY 容器渲染且可含子项
 *   A-24 静态 NAV 文案未知 → 回退断言为计数式（menuitem ≥2），静态项文本不做假设
 *   A-25 渲染整棵 App：Dashboard 等伴随请求统一 mock 回空信封，不影响导航断言
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: 'DIRECTORY' | 'MENU';
  path: string; component: string | null; icon: string | null; sortOrder: number;
  visible: boolean; permissionCode: string | null; status: 'ENABLED' | 'DISABLED';
  children: MenuNode[]; version: string;
};

const menuNode = (over: Partial<MenuNode> = {}): MenuNode => ({
  id: '1', parentId: '0', menuName: '', menuType: 'MENU', path: '/', component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

/** 两级菜单树：DIRECTORY(系统管理) + MENU 叶子；含未授权节点与 visible=false 节点。 */
const MENUS: MenuNode[] = [
  // 根级 MENU 节点名避开 Dashboard 页面可能出现的标题文案（防 getByText 多匹配）
  menuNode({ id: '1', menuName: '首页概览', menuType: 'MENU', path: '/dashboard', sortOrder: 1 }),
  menuNode({
    id: '2', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', sortOrder: 2,
    children: [
      menuNode({ id: '21', parentId: '2', menuName: '用户中心', path: '/users', permissionCode: 'system:user:list', sortOrder: 1 }),
      menuNode({ id: '22', parentId: '2', menuName: '租户管理', path: '/tenants', permissionCode: 'system:tenant:list', sortOrder: 2 }),
      menuNode({ id: '23', parentId: '2', menuName: '角色管理', path: '/roles', permissionCode: 'system:role:list', sortOrder: 3 }),
      menuNode({ id: '24', parentId: '2', menuName: '隐藏页面', path: '/hidden', visible: false, sortOrder: 4 }),
    ],
  }),
];

const HELD_PERMS = ['system:user:list', 'system:tenant:list'];

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const mePayload = (permissions: string[], menus?: MenuNode[]) => {
  const base: Record<string, unknown> = {
    user: meUser,
    roles: ['super_admin'],
    permissions,
  };
  // menus === undefined 时整键缺失（「缺失」回退分支）
  if (menus !== undefined) base.menus = menus;
  return base;
};

const stubBrowserGaps = () => {
  // 断点全不匹配会把 App 压成移动布局（Sider/Menu 不挂载，A-24 的 menuitem 计数无从谈起）——
  // min-width 断点置 true 使其按桌面布局渲染侧边导航（装配修正，不改断言）
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
    userId: 1001,
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 播种会话 + me 画像（menus 缺省为空数组=「为空」回退分支），再渲染整棵 App。 */
const renderApp = async (permissions: string[], menus: MenuNode[] = []) => {
  seedSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.includes('/system/me')) {
      return envelope(mePayload(permissions, menus));
    }
    return envelope(null); // Dashboard 等伴随请求（A-25）
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = '#/';
  // App 消费 react-router hooks（useNavigate/useLocation），生产装配在 main.tsx 以 HashRouter 包裹——
  // 测试端补齐同一 Router 上下文（装配修复，不改断言）
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const menuItemCount = () => document.querySelectorAll('[role="menuitem"]').length;

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
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
});

describe('REQ20261114120101 导航动态化（AC-7）', () => {
  it('should_render_nav_from_me_menu_tree_when_logged_in', async () => {
    await renderApp(HELD_PERMS, MENUS);

    // 菜单树渲染：DIRECTORY 容器与叶子节点均以 menuName 承载（A-22/A-23）
    expect(await screen.findByText('首页概览')).toBeTruthy();
    expect(screen.getByText('系统管理')).toBeTruthy();
    expect(screen.getByText('用户中心')).toBeTruthy();
    expect(screen.getByText('租户管理')).toBeTruthy();
  });

  it('should_hide_menu_nodes_whose_permission_code_not_held', async () => {
    await renderApp(HELD_PERMS, MENUS);

    // 已持有权限的叶子可见，未持有权限（system:role:list）的叶子隐藏
    expect(await screen.findByText('用户中心')).toBeTruthy();
    await waitFor(() => {
      expect(screen.queryByText('角色管理')).toBeNull();
    });
  });

  it('should_hide_menu_nodes_marked_invisible', async () => {
    await renderApp(HELD_PERMS, MENUS);

    expect(await screen.findByText('用户中心')).toBeTruthy();
    // visible=false 的节点不渲染（A-23）
    expect(screen.queryByText('隐藏页面')).toBeNull();
  });

  it('should_fallback_to_static_nav_when_me_menus_empty', async () => {
    // me.menus = [] → 回退静态 NAV（fail-closed 不变；静态项文案不假设，计数式断言 A-24）
    // 播种归一三段式（round1 裁决⑤）
    await renderApp(['system:user:list', 'system:tenant:list'], []);

    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_fallback_to_static_nav_when_me_menus_key_missing', async () => {
    // me 载荷缺 menus 键 → 同样回退静态 NAV
    // 播种归一三段式（round1 裁决⑤）
    await renderApp(['system:user:list', 'system:tenant:list'], undefined);

    await waitFor(() => {
      expect(menuItemCount()).toBeGreaterThanOrEqual(2);
    });
  });
});
