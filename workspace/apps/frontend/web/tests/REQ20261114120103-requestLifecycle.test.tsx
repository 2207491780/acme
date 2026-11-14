/**
 * REQ20261114120103 AC-4（组件面）：组件卸载/路由切换中止在途请求——fetch 层收到
 * AbortSignal 且卸载后 aborted；App 整棵装配下 hash 路由切换同语义。
 *
 * 装配照既有范本：
 *   - web/tests/REQ20261114120101-crudPages.test.tsx（fetch stubGlobal 弱匹配路由 + useMeStore
 *     三段式权限播种 + <AntApp> 包裹 + testTimeout ≥15000ms）
 *   - web/tests/REQ20261114120102-notificationBell.test.tsx（App 整棵装配：seedSession +
 *     endsWith('/me') 精确收敛 + HashRouter 上下文）
 *
 * 【表面假定（REQ20261114120103 套件内连续编号）】
 *   A-1 core 经 '@acme/core' 导出 i18next 实例（changeLanguage+t 鸭子型定位）——页面级独立渲染
 *       以该实例作 I18nextProvider 壳（默认单例场景无感，Provider 注入场景兜底）。
 *   A-11 卸载/路由切换中止：挂载请求经请求层传递 AbortSignal 且卸载时 abort（未接线=红灯，
 *       即 AC-4 缺口信号）；fetch 替身 abort 即拒绝 + 2.5s 兜底放行（防 15s 缺省超时定时器
 *       在用例结束后迟引爆产生跨用例 unhandled rejection）。
 *   A-13 路由面：hash 路由（web 既有口径），'#/tenants' 渲染 TenantsPage、'#/users' 渲染
 *       UsersPage（me.menus fixture path + crudPages 页面名口径）。
 *
 * 【setState-after-unmount 断言口径】React 18 已移除该告警，无法经 console 观测；以
 * 「fetch 层 signal aborted」作为「取消发生」的可观测等价点（委派单 AC-4 断言口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps, ReactNode } from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { App } from '../src/App';
import { TenantsPage } from '../src/pages/TenantsPage';
import * as coreModule from '@acme/core';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const TENANT_ROWS = [
  { id: '3001', tenantCode: 'acme', tenantName: '爱思蜜科技', status: 'ENABLED', remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0' },
];

const PERM_TENANTS_USERS = ['system:tenant:list', 'system:user:list'];

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const mePayload = () => ({
  user: meUser,
  roles: ['super_admin'],
  permissions: PERM_TENANTS_USERS,
  menus: [],
});

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase() };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase() };
};

/**
 * 挂起型 fetch 替身（A-11）：
 * - 捕获每个请求的 AbortSignal（存在即透传到位）；
 * - abort 即以 AbortError 拒绝（真实 fetch 语义）；
 * - 2.5s 真实定时器兜底放行数据（< 15s 缺省超时），保证未接线场景无迟引爆 rejection。
 */
const setupHangingTenantsApi = () => {
  const signals: AbortSignal[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.url.pathname.includes('/system/tenants')) {
      return new Promise<Response>((resolve, reject) => {
        const signal = init?.signal;
        if (signal instanceof AbortSignal) signals.push(signal);
        const abortErr = () => reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' }));
        signal?.addEventListener('abort', abortErr);
        const settle = setTimeout(() => resolve(envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length })), 2500);
        signal?.addEventListener('abort', () => clearTimeout(settle));
      });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, signals };
};

/** App 整棵装配（notificationBell renderAppWithNotify 口径）：/me 精确收敛 + 租户挂起 + 用户页空数据。 */
const setupAppApi = () => {
  const signals: AbortSignal[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    // endsWith('/me') 精确收敛（防 unread-count 类子串劫持，120102 裁决口径）
    if (r.method === 'GET' && p.endsWith('/me')) {
      return envelope(mePayload());
    }
    if (p.includes('/system/tenants')) {
      return new Promise<Response>((resolve, reject) => {
        const signal = init?.signal;
        if (signal instanceof AbortSignal) signals.push(signal);
        const abortErr = () => reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' }));
        signal?.addEventListener('abort', abortErr);
        const settle = setTimeout(() => resolve(envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length })), 2500);
        signal?.addEventListener('abort', () => clearTimeout(settle));
      });
    }
    if (p.includes('/system/users')) {
      return envelope({ records: [], total: 0 });
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, signals };
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

const seedMe = async (permissions: string[]) => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser,
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
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

// ————— i18n 实例鸭子型定位 + 页面渲染壳（同 i18nPages 用例 A-1 口径） —————
type I18nLike = {
  language?: string;
  changeLanguage: (lng: string) => Promise<unknown> | void;
  t: (key: string, opts?: unknown) => string;
};

const isI18nLike = (v: unknown): v is I18nLike =>
  !!v && typeof v === 'object'
  && typeof (v as I18nLike).changeLanguage === 'function'
  && typeof (v as I18nLike).t === 'function';

let cachedI18n: I18nLike | null = null;

const coreI18n = (): I18nLike => {
  if (cachedI18n) return cachedI18n;
  const mod = coreModule as unknown as Record<string, unknown>;
  for (const value of Object.values(mod)) {
    if (isI18nLike(value)) {
      cachedI18n = value;
      return value;
    }
  }
  throw new Error('A-1 未命中：@acme/core 导出面未发现 i18next 实例（changeLanguage+t 鸭子型判定）');
};

type ProviderI18n = ComponentProps<typeof I18nextProvider>['i18n'];

const RenderShell = ({ children }: { children: ReactNode }) => (
  <I18nextProvider i18n={coreI18n() as unknown as ProviderI18n}>
    <AntApp>{children}</AntApp>
  </I18nextProvider>
);

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

describe('REQ20261114120103 在途请求中止（AC-4 组件面）', () => {
  it('should_abort_in_flight_list_request_when_page_unmounts', async () => {
    await seedMe(['system:tenant:list']);
    const { signals } = setupHangingTenantsApi();
    const { unmount } = render(<RenderShell><TenantsPage /></RenderShell>);

    // 挂载请求已到达 fetch 层且携带 AbortSignal（未透传=红灯，AC-4 缺口信号，A-11）
    await waitFor(() => {
      expect(signals.length).toBeGreaterThanOrEqual(1);
    });

    unmount();

    // 卸载中止：signal 置 aborted（委派单 AC-4「AbortSignal 传到 fetch 层断言 aborted」）
    await waitFor(() => {
      expect(signals[0]?.aborted).toBe(true);
    });
  });

  it('should_abort_in_flight_request_when_route_switches', async () => {
    seedSession();
    const { fetchMock, signals } = setupAppApi();
    await useMeStore.getState().load();
    window.location.hash = '#/tenants';
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);

    // 租户页在途请求已挂起
    await waitFor(() => {
      expect(signals.length).toBeGreaterThanOrEqual(1);
    });

    // 路由切换（hash → 用户中心）：租户页卸载 → 在途请求中止（A-13）
    window.location.hash = '#/users';
    await waitFor(() => {
      const calls = (fetchMock.mock.calls as unknown as FetchArgs[])
        .map(([input, init]) => asRequest(input, init));
      expect(calls.some((c) => c.url.pathname.includes('/system/users'))).toBe(true);
    });
    await waitFor(() => {
      expect(signals[0]?.aborted).toBe(true);
    });
  });
});
