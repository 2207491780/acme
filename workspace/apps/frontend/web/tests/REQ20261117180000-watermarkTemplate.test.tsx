/**
 * REQ-20261117-180000：全局水印模板化——租户自配置水印内容（占位符全套）。
 *
 * 表面锚点：
 *   - SystemConfigPage 增「水印模板」输入（≤255 showCount）+ 占位符 chip 点击追加 + 实时预览
 *     + 未知完整占位符保存前置校验（与后端 platform-system-12-0002 同口径）；
 *     提交归一 trim 空串→null（null/空=仅用户名，沿用 REQ-20261116-230354 现状）。
 *   - App 级水印内容=renderWatermarkTemplate(template, 会话身份+me.deptName, now)；
 *     模板含 {date}/{time} 时注册 60s 分钟 tick 驱动刷新，缺省模板不注册。
 *
 * 装配照范本：
 *   - 页面用例：web/tests/REQ20261116230354-systemConfigPage.test.tsx（有状态 config 桩 + seedMe）。
 *   - App 用例：web/tests/REQ20261114120112-watermark.test.tsx（canvas fillText 探针 + 水印层判定）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { SystemConfigPage } from '../src/pages/SystemConfigPage';
import { useAuthStore, useMeStore, useSystemConfigStore, type AdminSystemConfigView } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

const SAVE_BUTTON = /保\s*存/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
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

const CONFIG_DEFAULT: AdminSystemConfigView = {
  watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
  footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0',
};

/** 有状态 config 桩（范本同款：PUT 成功更新 state，version 不匹配→业务失败）。 */
const setupConfigApi = (initial: AdminSystemConfigView) => {
  const state = { ...initial };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes('/system/config')) return envelope(null);
    if (r.method === 'GET') return envelope({ ...state });
    if (r.method === 'PUT') {
      const body = parseBody(r.body);
      if (String(body.version) !== state.version) {
        return envelope(null);
      }
      state.watermarkEnabled = Boolean(body.watermarkEnabled);
      state.captchaEnabled = Boolean(body.captchaEnabled);
      state.lockIdleTimeoutMs = Number(body.lockIdleTimeoutMs);
      state.footerCopyright = (body.footerCopyright as string | null) ?? null;
      state.footerVisible = body.footerVisible === true;
      state.watermarkTemplate = (body.watermarkTemplate as string | null) ?? null;
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

const ME_FIXTURE = {
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], deptId: '4001', createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions: ['system:config:list', 'system:config:update'],
  menus: [],
  deptName: '研发部',
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: '超级管理员',
    tenantCode: 'acme',
  }, false);
};

const seedMe = async (permissions: string[]) => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({ ...ME_FIXTURE, permissions })));
  await useMeStore.getState().load();
};

const renderPage = async (permissions: string[], initial: AdminSystemConfigView) => {
  await seedMe(permissions);
  seedSession();
  const api = setupConfigApi(initial);
  render(<AntApp><SystemConfigPage /></AntApp>);
  return api;
};

/** 占位符 chip 定位（Tag 文本=「{name} 中文名」，按 ant-tag 类收敛防父级重匹配）。 */
const chipOf = (label: string) =>
  screen.getByText((_, el) => el?.classList.contains('ant-tag') === true && el.textContent === label);

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, watermarkTemplate: null, lockIdleTimeoutMs: 0, version: '0', loaded: false });
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, watermarkTemplate: null, lockIdleTimeoutMs: 0, version: '0', loaded: false });
});

describe('REQ20261117180000 配置页水印模板入口', () => {
  it('should_insertTokenAndPreview_when_placeholderChipClicked', async () => {
    await renderPage(['system:config:list', 'system:config:update'], CONFIG_DEFAULT);
    const input = await screen.findByLabelText(/水印模板/);
    await waitFor(() => expect(input).toHaveProperty('value', ''));

    fireEvent.click(chipOf('{username} 登录账号'));
    await waitFor(() => expect(input).toHaveProperty('value', '{username}'));
    // 实时预览=当前会话身份渲染（与全局水印同口径）
    await screen.findByText(/预览：admin/);

    fireEvent.click(chipOf('{deptName} 部门'));
    await waitFor(() => expect(input).toHaveProperty('value', '{username}{deptName}'));
    await screen.findByText(/预览：admin研发部/);
  });

  it('should_blockSave_when_unknownPlaceholderPresent', async () => {
    const { fetchMock } = await renderPage(['system:config:list', 'system:config:update'], CONFIG_DEFAULT);
    const input = await screen.findByLabelText(/水印模板/);
    fireEvent.change(input, { target: { value: '{username} {bogus}' } });
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/不支持的占位符/);
    expect(callsOf(fetchMock, 'PUT')).toHaveLength(0);
  });

  it('should_saveAndPersistTemplate_when_valid', async () => {
    const { fetchMock } = await renderPage(['system:config:list', 'system:config:update'], CONFIG_DEFAULT);
    const input = await screen.findByLabelText(/水印模板/);
    fireEvent.change(input, { target: { value: '{username} {date} {time}' } });
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/成功/);
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]?.body).watermarkTemplate).toBe('{username} {date} {time}');
    });

    // 持久化回读：重挂载后表单仍为已保存模板
    cleanup();
    render(<AntApp><SystemConfigPage /></AntApp>);
    const reloaded = await screen.findByLabelText(/水印模板/);
    await waitFor(() => expect(reloaded).toHaveProperty('value', '{username} {date} {time}'));
  });

  it('should_normalizeBlankToNull_when_templateCleared', async () => {
    const { fetchMock } = await renderPage(
      ['system:config:list', 'system:config:update'],
      { ...CONFIG_DEFAULT, watermarkTemplate: '{username}' },
    );
    const input = await screen.findByLabelText(/水印模板/);
    await waitFor(() => expect(input).toHaveProperty('value', '{username}'));
    fireEvent.change(input, { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/成功/);
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]?.body).watermarkTemplate).toBeNull();
    });
  });

  it('should_disableTemplateInput_when_noUpdatePermission', async () => {
    await renderPage(['system:config:list'], CONFIG_DEFAULT);
    const input = await screen.findByLabelText(/水印模板/);
    await waitFor(() => expect((input as HTMLInputElement).disabled).toBe(true));
    expect(screen.queryByRole('button', { name: SAVE_BUTTON })).toBeNull();
  });
});

// ————— App 级模板渲染（canvas fillText 探针，范本 REQ20261114120112 同款） —————
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

const watermarkLayer = (root: HTMLElement) =>
  Array.from(root.querySelectorAll('div')).find(
    (d) =>
      d.style.pointerEvents === 'none' &&
      /data:image/.test(d.style.backgroundImage || ''),
  ) ?? null;

const MENUS = [
  {
    id: '1', parentId: '0', menuName: '首页概览', menuType: 'MENU', path: '/dashboard', component: null,
    icon: null, sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  },
];

let configFixture: AdminSystemConfigView = { ...CONFIG_DEFAULT };
const intervals: Array<{ fn: () => void; ms: number }> = [];

const renderAppWithTemplate = async (template: string | null) => {
  configFixture = { ...CONFIG_DEFAULT, watermarkTemplate: template };
  seedSession();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ ...ME_FIXTURE, menus: MENUS });
    }
    if (url.pathname.endsWith('/system/config')) {
      return envelope(configFixture);
    }
    return envelope(null);
  }));
  await useMeStore.getState().load();
  await useSystemConfigStore.getState().load();
  // 分钟 tick 探针：捕获 60s 间隔注册（TICK 1200ms 等其余间隔不干扰判定）
  const realSetInterval = globalThis.setInterval;
  intervals.length = 0;
  vi.stubGlobal('setInterval', (fn: () => void, ms: number) => {
    intervals.push({ fn, ms });
    return realSetInterval(fn, ms);
  });
  window.location.hash = '#/dashboard';
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

describe('REQ20261117180000 App 全局水印模板渲染', () => {
  beforeEach(() => {
    fillTextSpy.mockClear();
    stubCanvas();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('should_renderIdentityTokens_when_templateConfigured', async () => {
    await renderAppWithTemplate('{username}|{nickname}|{userId}|{tenantCode}|{deptName}');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    const printed = fillTextSpy.mock.calls.map((c) => String(c[0]));
    expect(printed.some((text) => text.includes('admin|超级管理员|1001|acme|研发部'))).toBe(true);
  });

  it('should_renderUsernameOnly_when_templateNull', async () => {
    await renderAppWithTemplate(null);
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    const printed = fillTextSpy.mock.calls.map((c) => String(c[0]));
    expect(printed.some((text) => text === 'admin')).toBe(true);
    // 缺省模板不注册分钟 tick
    expect(intervals.some((i) => i.ms === 60_000)).toBe(false);
  });

  it('should_registerMinuteTickAndRerender_when_timePlaceholderPresent', async () => {
    // 仅 fake Date（间隔/waitFor 走真实计时）：tick 后跨分钟内容变化才触发 Watermark 重绘
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-17T10:00:30'));
    await renderAppWithTemplate('{username} {time}');
    await waitFor(() => expect(watermarkLayer(document.body)).not.toBeNull());
    const printed = fillTextSpy.mock.calls.map((c) => String(c[0]));
    // zh-CN 缺省时间形态 HH:mm（core formatTimeOnly 口径）
    expect(printed.some((text) => text === 'admin 10:00')).toBe(true);

    const tick = intervals.find((i) => i.ms === 60_000);
    expect(tick).toBeDefined();
    vi.setSystemTime(new Date('2026-11-17T10:01:30'));
    // antd Watermark 重绘经 raf 去抖（16ms 锁窗内丢弃）：跨窗后二次触发确保落布
    await act(async () => {
      tick?.fn();
      await new Promise((resolve) => setTimeout(resolve, 50));
      tick?.fn();
    });
    await waitFor(() => {
      const after = fillTextSpy.mock.calls.map((c) => String(c[0]));
      expect(after.some((text) => text === 'admin 10:01')).toBe(true);
    });
  });
});