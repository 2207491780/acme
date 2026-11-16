/**
 * REQ-20261116-230354 AC-5/AC-6：SystemConfigPage 管理页契约用例。
 *
 * 装配照范本 web/tests/REQ20261114120101-crudPages.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + testTimeout 放宽。
 * 命名以契约§命名裁定为准：页面命名导出 SystemConfigPage（路径惯例 web/src/pages/<Key>Page.tsx），
 * store useSystemConfigStore，端点 GET/PUT /admin/v1/system/config。
 *
 * AC-5：管理员（system:config:update）改水印/时长并保存 → 成功提示 + PUT 载荷正确 + 重新加载仍为新值。
 * AC-6（页面 face）：无 update 权限直达页面 → 保存能力 fail-closed 不渲染。
 * 乐观锁冲突（PUT 业务失败信封 code≠'0'，HTTP 200）→ 页面提示重试。
 *
 * 装配假定（契约未钉死 UI 文案，TL 执行时按实际 i18n 校准）：
 *   - 时长 Select 选项显示名含「5 分钟」（候选兜底 5分钟/5 min）——AMB-UI-1；
 *   - 成功提示文案含「成功」、冲突提示含「冲突」或「重试」——AMB-UI-2。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { SystemConfigPage } from '../src/pages/SystemConfigPage';
import { useAuthStore, useMeStore, useSystemConfigStore } from '@acme/core';
import type { AdminSystemConfigView } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

const SAVE_BUTTON = /保\s*存/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (message: string) => new Response(
  JSON.stringify({ success: false, code: 'platform-system-08-0003', message, data: null }), { status: 200 },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes('/system/config') && r.method === method);

/** 有状态 config 桩：PUT 成功更新 state（供「刷新后仍为新值」回读），version 不匹配→业务失败。 */
const setupConfigApi = (initial: AdminSystemConfigView) => {
  const state = { ...initial };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes('/system/config')) return envelope(null);
    if (r.method === 'GET') return envelope({ ...state });
    if (r.method === 'PUT') {
      const body = parseBody(r.body);
      if (String(body.version) !== state.version) {
        return failureEnvelope('数据已被他人修改，请刷新后重试');
      }
      state.watermarkEnabled = Boolean(body.watermarkEnabled);
      state.lockIdleTimeoutMs = Number(body.lockIdleTimeoutMs);
      state.version = String(Number(state.version) + 1);
      return envelope({ ...state });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, onchange: null,
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
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

const renderPage = async (permissions: string[], initial: AdminSystemConfigView) => {
  await seedMe(permissions);
  const api = setupConfigApi(initial);
  render(<AntApp><SystemConfigPage /></AntApp>);
  return api;
};

/** 时长 Select 点选（antd5 virtual 镜像坑照范本 delta-4：按显示名文本点可见选项节点）。 */
const pickIdleOption = async (candidates: string[]) => {
  fireEvent.mouseDown(screen.getByRole('combobox'));
  for (const c of candidates) {
    try {
      fireEvent.click(await screen.findByText(c, {}, { timeout: 2000 }));
      return;
    } catch {
      // 候选逐个尝试（i18n 文案未钉死，AMB-UI-1）
    }
  }
  throw new Error(`未找到时长选项: ${candidates.join(' / ')}`);
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0', loaded: false });
});

describe('REQ20261116230354 AC-5 系统配置管理页', () => {
  it('should_saveAndPersistNewValues_when_adminUpdatesConfig', async () => {
    const { fetchMock } = await renderPage(
      ['system:config:list', 'system:config:update'],
      { watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' },
    );

    // 表单回填缺省值：水印 Switch 开
    const switchEl = await screen.findByRole('switch');
    await waitFor(() => expect(switchEl.getAttribute('aria-checked')).toBe('true'));

    // 改两项：水印关 + 时长 5 分钟（300000ms）
    fireEvent.click(switchEl);
    await pickIdleOption(['5 分钟', '5分钟', '5 min']);
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    // 成功提示（AMB-UI-2）
    await screen.findByText(/成功/);

    // PUT 载荷 = 契约形状 { watermarkEnabled, lockIdleTimeoutMs, version }
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]?.body)).toEqual({ watermarkEnabled: false, lockIdleTimeoutMs: 300000, version: '0' });
    });

    // 持久化：卸载后重新加载页面，GET 回读仍为新值（桩 state 已被 PUT 更新）
    cleanup();
    render(<AntApp><SystemConfigPage /></AntApp>);
    const reloaded = await screen.findByRole('switch');
    await waitFor(() => expect(reloaded.getAttribute('aria-checked')).toBe('false'));
    expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
  });

  it('should_promptRetry_when_updateVersionConflict', async () => {
    await seedMe(['system:config:list', 'system:config:update']);
    // GET 正常；PUT 一律业务失败（HTTP 200 信封 code≠'0'，乐观锁冲突口径）
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.url.pathname.includes('/system/config') && r.method === 'PUT') {
        return failureEnvelope('数据已被他人修改，请刷新后重试');
      }
      if (r.url.pathname.includes('/system/config')) {
        return envelope({ watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
      }
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<AntApp><SystemConfigPage /></AntApp>);

    const switchEl = await screen.findByRole('switch');
    fireEvent.click(switchEl);
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    // 冲突提示重试（契约：乐观锁冲突提示重试；文案含「冲突」或「重试」，AMB-UI-2）
    await screen.findByText(/冲突|重试/);
    expect(callsOf(fetchMock, 'PUT')).toHaveLength(1);
  });
});

describe('REQ20261116230354 AC-6 保存能力 fail-closed', () => {
  it('should_notRenderSave_when_onlyListPermission', async () => {
    await renderPage(['system:config:list'], { watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
    await screen.findByRole('switch');
    expect(screen.queryByRole('button', { name: SAVE_BUTTON })).toBeNull();
  });

  it('should_notRenderSave_when_noPermissions', async () => {
    await renderPage([], { watermarkEnabled: true, lockIdleTimeoutMs: 0, version: '0' });
    // 无任何权限直达路由：保存能力不渲染（fail-closed，crud.can 模式）
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: SAVE_BUTTON })).toBeNull();
    });
    expect(useMeStore.getState().me?.permissions ?? []).not.toContain('system:config:update');
  });
});