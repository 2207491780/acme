/**
 * REQ-20261114-120112 AC-3：全局水印——默认开启、内容=当前用户账号、关闭后立即消失。
 * REQ-20261116-230354 适配：水印开关由个人偏好（uiPrefsStore，已移除）上收为租户系统配置
 * （useSystemConfigStore，内存态，引导时经 GET /admin/v1/system/config 加载）；
 * 「持久化」语义随之改为服务端持久化——本套件以 GET config fixture 承载关闭态，
 * load() 后重挂载水印保持消失（AC-7 的测试面锚点；新 AC 用例由 tester 并行补充）。
 *
 * 装配照既有范本：
 *   - web/tests/REQ20261114120103-errorBoundary.test.tsx（App 整棵装配：seedSession +
 *     endsWith('/me') 精确收敛 + HashRouter + MENUS fixture + <AntApp> 包裹 + matchMedia/
 *     ResizeObserver stub）。
 *   - jsdom 无 canvas 渲染面 → spy HTMLCanvasElement.prototype.getContext/toDataURL，
 *     以 2D 上下文代理承接（fillText 探针用于断言水印内容=账号）。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号；A-13/A-15 随 230354 命名裁定更新）】
 *   A-12 水印=App 级 antd Watermark 包裹内容区（契约变更清单）；水印层判定=inline style
 *        pointer-events:none 且 background-image 为 data:image 的空 div（R3 归因修正：
 *        rc-watermark 实现为纯 background-image 标记层，无 img/canvas 子元素）。
 *   A-13 租户配置 store：core barrel 命名导出 useSystemConfigStore（REQ-20261116-230354
 *        命名裁定），表面 { watermarkEnabled: boolean; lockIdleTimeoutMs: number;
 *        version: string; loaded: boolean; load(); update() }，内存态不持久化；
 *        默认 watermarkEnabled=true（契约确认记录④缺省值）。
 *   A-14 水印内容=当前用户账号（契约确认记录②）→ 会话 username 'admin' 经 fillText 落布。
 *   A-15 持久化=服务端（租户配置行）：以 GET /system/config 返回关闭态 fixture + load()
 *        模拟「他人保存后本人刷新加载」，重挂载水印保持消失；不再断言 localStorage。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore, useSystemConfigStore, type AdminSystemConfigView } from '@acme/core';

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

/** 服务端租户配置 fixture（A-15：持久化语义的承载面，可被用例改写为关闭态）。 */
let configFixture: AdminSystemConfigView = { watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, version: '0' };

const renderAppAt = async (hash: string) => {
  seedSession();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: HELD_PERMS, menus: MENUS });
    }
    if (url.pathname.endsWith('/system/config')) {
      return envelope(configFixture);
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

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  // store 为模块级单例：复位缺省态（A-13），防上一用例关闭态跨用例残留
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
  configFixture = { watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, version: '0' };
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
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
});

// ————— AC-3（230354 后水印源=租户配置，AC-7 同表面） —————
describe('REQ20261114120112 全局水印（AC-3）', () => {
  it('should_render_account_watermark_when_default_on', async () => {
    await renderAppAt('#/dashboard'); // 不预设配置 → 缺省开启（A-13）
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    // 内容=当前用户账号（A-14）
    const printed = fillTextSpy.mock.calls.map((c) => String(c[0]));
    expect(printed.some((t) => t.includes('admin'))).toBe(true);
  });

  it('should_remove_watermark_immediately_when_setting_disabled', async () => {
    await renderAppAt('#/dashboard');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    // 配置关闭（store 级开关；管理页保存经 update() 落同一状态面）
    act(() => { useSystemConfigStore.setState({ watermarkEnabled: false }); });
    await waitFor(() => expect(watermarkLayer(document.body)).toBeNull()); // 立即消失
  });

  it('should_persist_watermark_disabled_when_reloaded', async () => {
    await renderAppAt('#/dashboard');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    // 服务端持久化（A-15）：租户配置行已为关闭态 → 刷新/重新登录经 load() 加载生效
    configFixture = { watermarkEnabled: false, captchaEnabled: false, lockIdleTimeoutMs: 0, version: '1' };
    await act(async () => { await useSystemConfigStore.getState().load(); });
    expect(useSystemConfigStore.getState().watermarkEnabled).toBe(false);
    await waitFor(() => expect(watermarkLayer(document.body)).toBeNull());
    // 重挂载控制组：引导再次 load() 取服务端关闭态 → 水印保持消失
    cleanup();
    window.location.hash = '#/dashboard';
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
    await waitFor(() => expect(watermarkLayer(document.body)).toBeNull());
  });
});