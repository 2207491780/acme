/**
 * REQ-20261116-230354 AC-6（菜单显隐）/AC-7（全局水印强制生效）应用外壳契约用例。
 *
 * 装配口径 = TL 回填契约确认记录⑩（实际实现口径）：
 *   - 外壳 web/src/App.tsx 命名导出 App；render(<AntApp><HashRouter><App /></HashRouter></AntApp>)。
 *   - 登录态播种 useAuthStore.getState().setSession({ accessToken, expiresAtMs, userId:number,
 *     username, nickname, tenantCode }, false)；fetch 桩信封 {success:true,code:'0',message:null,data}；
 *     /system/me 回 mePayload(permissions, menus)；/system/config 必须桩（App 引导随 me 加载租户配置）；
 *     其余伴随请求 envelope(null)。再 await useMeStore.getState().load()。
 *   - 水印 DOM 特征：App 级 antd Watermark（content=session.username）启用时渲染带
 *     style.backgroundImage 含 'data:image' 的覆盖 div；禁用/未登录不存在。实现仅换数据源不改结构。
 *   - 菜单由 me().menus 驱动；无 system:config:list → menus 不含节点 → 菜单内无「系统配置」。
 *     菜单断言限定 role="menu" 容器内（避免与页面标题/面包屑文本串扰）。
 *
 * 路由统一落 #/system/config（有权限→配置页渲染；无权限→fail-closed，均不影响外壳菜单/水印断言）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore, useSystemConfigStore } from '@acme/core';
import type { AdminSystemConfigView } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: string; path: string;
  component: string | null; icon: string | null; sortOrder: number; visible: boolean;
  permissionCode: string | null; status: string; version: string; children: MenuNode[];
};

const configMenuNode: MenuNode = {
  id: '12', parentId: '1', menuName: '系统配置', menuType: 'MENU', path: '/system/config',
  component: 'system/config/index', icon: null, sortOrder: 99, visible: true,
  permissionCode: 'system:config:list', status: 'ENABLED', version: '0', children: [],
};

/** mePayload 照范本 seedMe 形状 + nav 范本 MenuNode 形状（TL 确认记录⑩）。 */
const mePayload = (permissions: string[], withConfigMenu: boolean) => ({
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions,
  menus: [
    {
      id: '1', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system',
      component: null, icon: null, sortOrder: 1, visible: true, permissionCode: null,
      status: 'ENABLED', version: '0',
      children: [
        {
          id: '11', parentId: '1', menuName: '用户中心', menuType: 'MENU', path: '/users',
          component: 'users', icon: null, sortOrder: 1, visible: true,
          permissionCode: 'system:user:list', status: 'ENABLED', version: '0', children: [],
        },
        ...(withConfigMenu ? [configMenuNode] : []),
      ],
    },
  ] as MenuNode[],
});

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const setupShellApi = (permissions: string[], withConfigMenu: boolean, config: AdminSystemConfigView) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p.includes('/system/me')) return envelope(mePayload(permissions, withConfigMenu));
    if (p.includes('/system/config')) return envelope({ ...config });
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

const stubBrowserGaps = () => {
  // 断点全不匹配会把 App 压成移动布局（Sider/Menu 不挂载）——min-width 断点置 true
  // 按桌面布局渲染侧边导航（装配修正照 nav 范本 A-24，不改断言）
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

// jsdom 无 canvas 2D 上下文 → antd Watermark 无法生成 data:image 覆盖层；
// 照 watermark 范本 A-12 补渲染面 stub（装配修正，不改断言）
const makeCtx = () => {
  const fns = new Map<string | symbol, unknown>();
  return new Proxy({} as CanvasRenderingContext2D, {
    get(_t, prop) {
      if (prop === 'measureText') return () => ({ width: 24 });
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

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
    userId: '1001', username: 'admin', nickname: null, tenantCode: 'default',
  }, false);
};

const renderShell = async (
  permissions: string[], withConfigMenu: boolean, config: AdminSystemConfigView,
) => {
  seedSession();
  const fetchMock = setupShellApi(permissions, withConfigMenu, config);
  await useMeStore.getState().load();
  const utils = render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock, ...utils };
};

/** 水印覆盖 div 特征（TL 确认记录⑩）：style.backgroundImage 含 data:image 的 div。 */
const watermarkDivs = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('div')).filter((d) => d.style.backgroundImage.includes('data:image'));

/** 菜单文本限定 role="menu" 容器（避免页面标题/面包屑串扰）。 */
const menuText = () =>
  Array.from(document.querySelectorAll('[role="menu"]')).map((m) => m.textContent ?? '').join('\n');

beforeEach(() => {
  window.location.hash = '#/system/config';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
  stubBrowserGaps();
  stubCanvas();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  window.location.hash = '';
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
});

describe('REQ20261116230354 AC-6 菜单显隐（me().menus 驱动）', () => {
  it('should_hideSystemConfigMenu_when_meMenusLackConfigNode', async () => {
    await renderShell(['system:user:list'], false, { watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0' });
    // 外壳就绪锚：既有菜单「用户中心」可见
    await waitFor(() => expect(menuText()).toContain('用户中心'));
    expect(menuText()).not.toContain('系统配置');
  });

  it('should_showSystemConfigMenu_when_meMenusContainConfigNode', async () => {
    await renderShell(
      ['system:user:list', 'system:config:list'], true,
      { watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0' },
    );
    await waitFor(() => expect(menuText()).toContain('系统配置'));
  });
});

describe('REQ20261116230354 AC-7 全局水印强制生效（DOM）', () => {
  it('should_renderGlobalWatermark_when_tenantWatermarkEnabled', async () => {
    const { container } = await renderShell(
      ['system:config:list', 'system:config:update'], true,
      { watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0' },
    );
    await waitFor(() => expect(watermarkDivs(container).length).toBeGreaterThan(0));
  });

  it('should_notRenderGlobalWatermark_when_tenantWatermarkDisabled', async () => {
    const { container } = await renderShell(
      ['system:config:list', 'system:config:update'], true,
      { watermarkEnabled: false, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0' },
    );
    // 引导完成锚：租户配置已加载且为禁用态，再断言水印不存在
    await waitFor(() => {
      expect(useSystemConfigStore.getState().loaded).toBe(true);
      expect(useSystemConfigStore.getState().watermarkEnabled).toBe(false);
    });
    // 页面就绪锚：REQ-20261117-011514 后配置页含水印+验证码双开关，按 label 定位避免多元素歧义
    await screen.findByLabelText(/全局水印/);
    expect(watermarkDivs(container)).toHaveLength(0);
  });
});