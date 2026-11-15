/**
 * REQ-20261114-120112 AC-3：全局水印——默认开启、内容=当前用户账号、Settings 关闭后立即
 * 消失并持久化（antd Watermark，零新依赖）。
 *
 * 装配照既有范本：
 *   - web/tests/REQ20261114120103-errorBoundary.test.tsx（App 整棵装配：seedSession +
 *     endsWith('/me') 精确收敛 + HashRouter + MENUS fixture + <AntApp> 包裹 + matchMedia/
 *     ResizeObserver stub）。
 *   - jsdom 无 canvas 渲染面 → spy HTMLCanvasElement.prototype.getContext/toDataURL，
 *     以 2D 上下文代理承接（fillText 探针用于断言水印内容=账号）。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号）】
 *   A-12 水印=App 级 antd Watermark 包裹内容区（契约变更清单）；水印层判定=inline style
 *        pointer-events:none 且 background-image 为 data:image 的空 div（R3 归因修正：
 *        rc-watermark 实现为纯 background-image 标记层，无 img/canvas 子元素）。
 *   A-13 UI 偏好 store：core barrel 命名导出 useUiPrefsStore（「水印/锁屏设置项进既有
 *        settings 体系」，具体既有 store 名未契约化 → TL 门禁对齐），表面
 *        { watermarkEnabled: boolean; setWatermarkEnabled(v: boolean) }，经 zustand persist
 *        落 localStorage；默认 watermarkEnabled=true（契约「管理端默认开」）。
 *   A-14 水印内容=当前用户账号（契约确认记录②）→ 会话 username 'admin' 经 fillText 落布。
 *   A-15 持久化断言不锁 persist 键名：遍历 localStorage 值包含 '"watermarkEnabled":false'
 *        即判持久化成功；Settings 页面的开关接线由 TL 门禁（AC-6/ui:guide）覆盖，本组
 *        锁定「关闭→立即消失+持久化」的响应链。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore, useUiPrefsStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// ————— canvas 渲染面 stub（A-12：jsdom 无 2D 上下文；fillText 探针承接水印内容断言） —————
const fillTextSpy = vi.fn();
const makeCtx = () => {
  const fns = new Map<string | symbol, unknown>();
  return new Proxy({} as CanvasRenderingContext2D, {
    get(_t, prop) {
      if (prop === 'measureText') return () => ({ width: 24 });
      if (prop === 'fillText') return fillTextSpy;
      if (!fns.has(prop)) fns.set(prop, vi.fn());
      return fns.get(prop);
    },
    set() { return true; },
  });
};
const stubCanvas = () => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => makeCtx());
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(
    () => 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==',
  );
};

// ————— App 装配 fixture（errorBoundary 范本口径） —————
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

const renderAppAt = async (hash: string) => {
  seedSession();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: HELD_PERMS, menus: MENUS });
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  }));
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— 水印层判定（A-12） —————
const watermarkLayer = (root: HTMLElement) =>
  Array.from(root.querySelectorAll('div')).find(
    (d) =>
      d.style.pointerEvents === 'none' &&
      /data:image/.test(d.style.backgroundImage || ''),
  ) ?? null;

const prefsApi = () => useUiPrefsStore.getState() as unknown as {
  watermarkEnabled: boolean;
  setWatermarkEnabled: (v: boolean) => void;
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  // R5 归因：useUiPrefsStore 是模块级单例，上一用例 setWatermarkEnabled(false) 的状态跨用例
  // 残留 → 「默认开启」前提不成立；storage 清理后重置 store 内存态
  useUiPrefsStore.getState().setWatermarkEnabled(true);
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  fillTextSpy.mockClear();
  stubBrowserGaps();
  stubCanvas();
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear(); // 共享环境脏数据清理（A-13 persist 落盘）
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-3 —————
describe('REQ20261114120112 全局水印（AC-3）', () => {
  it('should_render_account_watermark_when_default_on', async () => {
    await renderAppAt('#/dashboard'); // 不预设偏好 → 默认开启（A-13）
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    // 内容=当前用户账号（A-14）
    const printed = fillTextSpy.mock.calls.map((c) => String(c[0]));
    expect(printed.some((t) => t.includes('admin'))).toBe(true);
  });

  it('should_remove_watermark_immediately_when_setting_disabled', async () => {
    await renderAppAt('#/dashboard');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    act(() => { prefsApi().setWatermarkEnabled(false); }); // Settings 关闭（A-15：store 级开关）
    await waitFor(() => expect(watermarkLayer(document.body)).toBeNull()); // 立即消失
  });

  it('should_persist_watermark_disabled_when_reloaded', async () => {
    await renderAppAt('#/dashboard');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    act(() => { prefsApi().setWatermarkEnabled(false); });
    // 持久化：localStorage 出现关闭态（不锁 persist 键名，A-15）
    const persisted = Object.keys(window.localStorage).map((k) => String(window.localStorage.getItem(k)));
    expect(persisted.some((v) => v.includes('"watermarkEnabled":false'))).toBe(true);
    // 重挂载控制组：storage 不清 → 水印保持消失
    cleanup();
    window.location.hash = '#/dashboard';
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
    await waitFor(() => expect(watermarkLayer(document.body)).toBeNull());
  });
});
