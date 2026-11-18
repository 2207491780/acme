/**
 * REQ-20261119-040838：APP 扫码登录（web 面）契约用例。
 *
 * AC-6（扫码 Tab）：登录页扫码 Tab 渲染二维码（qrcode 本地 dataURL，200px）；
 *   轮询 confirmed 且 token 非空 → setSession（userId wire string）进入主界面；
 *   scanned 状态文案呈现；轮询在卸载/终态停止；「刷新二维码」重建 ticket 重启轮询。
 * AC-9（api/mock 面）：qrCreate/qrPoll/qrCancel 路径、载荷与公开端点口径（不带 Authorization）。
 *
 * 装配照范本 web/tests/REQ20261117011514-captchaSwitch.test.tsx：fetch stubGlobal +
 * <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + testTimeout 放宽 + MemoryRouter（useNavigate，
 * 另挂 '/' 主界面探针路由断言跳转）；fetch 桩用【精确 pathname】路由。
 * 轮询用 fake timers（范本 REQ20261114120102-wsClient 的 advanceTimersByTimeAsync 口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { LoginPage } from '../src/pages/LoginPage';
import { authApi, useAuthStore, type QrTicketStatusView, type TokenIssued } from '@acme/core';

// 全量并行运行时整机负载极高（jsdom + antd 重渲染并发），单测放宽至 60s 防饿死误报
vi.setConfig({ testTimeout: 60000 });

// ————— 行为常量 —————
const QR_TAB = '扫码登录';
const QR_IMG_LABEL = '登录二维码';
const QR_STATUS_TESTID = 'qr-status';
const MAIN_PROBE = 'main-probe';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

// ————— 扫码域有状态桩（tenants / qr-tickets 创建/轮询，精确 pathname 路由） —————

const confirmedToken = (): TokenIssued => ({
  accessToken: 'qr-access-token',
  tokenType: 'Bearer',
  expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  userId: '2002',
  username: 'qr-employee',
  nickname: '扫码员工',
});

interface QrStubOptions {
  /** 轮询响应序列（按 GET 次序取用；耗尽后取末项；默认恒 created）。 */
  pollScript?: QrTicketStatusView[];
}

const setupQrApi = (options: QrStubOptions = {}) => {
  const state = {
    creates: 0,
    polls: 0,
    pollTicketIds: [] as string[],
    createAuthHeaders: [] as (string | null)[],
    pollAuthHeaders: [] as (string | null)[],
    lastTicketId: '',
  };
  const script = options.pollScript ?? [{ status: 'created', token: null }];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    const authHeader = new Headers(init?.headers).get('Authorization');
    if (p === '/admin/v1/auth/tenants') {
      return envelope([{ tenantCode: 'acme', tenantName: 'ACME 总部' }]);
    }
    if (p === '/admin/v1/auth/captcha-policy') {
      return envelope({ captchaEnabled: false });
    }
    if (p === '/admin/v1/auth/public-config') {
      return envelope({ footerCopyright: null, footerVisible: false });
    }
    if (p === '/admin/v1/auth/qr-tickets' && r.method === 'POST') {
      state.creates += 1;
      state.createAuthHeaders.push(authHeader);
      state.lastTicketId = `tk-${state.creates}`;
      return envelope({
        ticketId: state.lastTicketId,
        qrPayload: JSON.stringify({ t: state.lastTicketId }),
        expiresInSeconds: 120,
      });
    }
    if (p.startsWith('/admin/v1/auth/qr-tickets/') && r.method === 'GET') {
      state.polls += 1;
      state.pollTicketIds.push(p.slice('/admin/v1/auth/qr-tickets/'.length));
      state.pollAuthHeaders.push(authHeader);
      const view = script[Math.min(state.polls, script.length) - 1]!;
      return envelope(view);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderLogin = () =>
  render(
    <AntApp>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div data-testid={MAIN_PROBE}>MAIN</div>} />
        </Routes>
      </MemoryRouter>
    </AntApp>,
  );

/** 激活扫码 Tab（antd Tabs 惰性挂载：点击后面板才创建票据）。 */
const openQrTab = async () => {
  fireEvent.click(screen.getByRole('tab', { name: QR_TAB }));
  // 创建 POST → toDataURL → 首查轮询：fake timers 下冲刷微任务链
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
};

const drain = async (ms = 0) => {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
};

// fake timers 下禁用 RTL waitFor（无 jest 集成，轮询 interval 被桩死会挂起）——统一用 drain+直接断言
const clickEl = async (el: HTMLElement) => {
  await act(async () => { fireEvent.click(el); await vi.advanceTimersByTimeAsync(0); });
};

const resetStores = () => {
  useAuthStore.getState().clear();
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
  stubBrowserGaps();
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
});

// ————— AC-6 扫码 Tab —————

describe('REQ20261119040838 AC-6 web 扫码登录', () => {
  it('should_renderQrCodeDataUrl_when_scanTabActivated', async () => {
    const { state } = setupQrApi();
    renderLogin();

    // 未激活扫码 Tab 前不创建票据（账密 Tab 惰性共存，既有表单不受 Tabs 结构影响）
    expect(screen.queryByAltText(QR_IMG_LABEL)).toBeNull();

    await openQrTab();

    // 创建票据 POST：公开端点不带 Authorization；返回体作为 qrPayload 渲染为 200px 二维码
    expect(state.creates).toBe(1);
    expect(state.createAuthHeaders[0]).toBeNull();
    const img = screen.getByAltText(QR_IMG_LABEL) as HTMLImageElement;
    expect(img.getAttribute('src')).toContain('data:image/png;base64,');
    expect(img.getAttribute('width')).toBe('200');
    // 状态提示：待扫描（首查 created）
    expect(screen.getByTestId(QR_STATUS_TESTID).textContent).toContain('待扫描');
  });

  it('should_setSessionWithWireStringUserIdAndEnterMain_when_pollConfirmed', async () => {
    const { state } = setupQrApi({
      pollScript: [
        { status: 'created', token: null },
        { status: 'scanned', token: null },
        { status: 'confirmed', token: confirmedToken() },
      ],
    });
    renderLogin();
    await openQrTab();

    // 首查 created → 已渲染票据；后续轮询推进 scanned（文案呈现 APP 侧待确认提示）
    await drain(1500);
    expect(screen.getByTestId(QR_STATUS_TESTID).textContent).toContain('请在手机上点击确认');
    await drain(1500);

    // confirmed：写入确认者 B 端会话（userId wire string）并跳转主界面探针
    expect(screen.getByTestId(MAIN_PROBE)).toBeTruthy();
    expect(state.pollTicketIds.every((id) => id === 'tk-1')).toBe(true);
    const session = useAuthStore.getState().session;
    expect(session).not.toBeNull();
    expect(typeof session!.userId).toBe('string');
    expect(session!.userId).toBe('2002');
    expect(session!.accessToken).toBe('qr-access-token');
    expect(session!.username).toBe('qr-employee');
    // confirmed 终态停止轮询：跳转后不再有新 GET
    const pollsAtConfirmed = state.polls;
    await drain(4500);
    expect(state.polls).toBe(pollsAtConfirmed);
  });

  it('should_stopPolling_when_unmounted', async () => {
    const { state } = setupQrApi();
    renderLogin();
    await openQrTab();

    // 首查立即 1 次；两次间隔轮询（1.5s/次）
    expect(state.polls).toBeGreaterThanOrEqual(1);
    await drain(1500);
    await drain(1500);
    const pollsBeforeUnmount = state.polls;
    expect(pollsBeforeUnmount).toBeGreaterThanOrEqual(3);

    // 卸载后推进 10s：不再产生新轮询（AC-6 页面卸载停止）
    cleanup();
    await drain(10_000);
    expect(state.polls).toBe(pollsBeforeUnmount);
  });

  it('should_recreateTicketAndRestartPolling_when_refreshClicked', async () => {
    const { state } = setupQrApi({
      pollScript: [{ status: 'expired', token: null }],
    });
    renderLogin();
    await openQrTab();
    expect(state.creates).toBe(1);

    // expired 终态停止轮询 → 面板提示可刷新；点击「刷新二维码」重建票据
    expect(screen.getByTestId(QR_STATUS_TESTID).textContent).toContain('过期');
    const pollsBefore = state.polls;
    await drain(4500);
    expect(state.polls).toBe(pollsBefore);

    // AntD 图标按钮可访问名含图标文本（reload），以 data-testid 定位（AC-6 刷新重建票据）
    await clickEl(screen.getByTestId('qr-refresh'));
    await drain(0);
    await drain(0);

    // 新票据创建（POST 第 2 次），后续轮询 GET 使用新 ticketId
    expect(state.creates).toBe(2);
    expect(screen.getByTestId(QR_STATUS_TESTID).textContent).toContain('过期');
    await drain(1500);
    const newTicketPolls = state.pollTicketIds.filter((id) => id === 'tk-2');
    expect(newTicketPolls.length).toBeGreaterThanOrEqual(1);
  });
});

// ————— AC-9 api 层（路径 / 载荷 / 公开端点口径） —————

describe('REQ20261119040838 qr api 层', () => {
  it('should_postQrTicketsWithoutAuth_when_qrCreateCalled', async () => {
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope({ ticketId: 'tk-x', qrPayload: '{"t":"tk-x"}', expiresInSeconds: 120 });
    }));

    const view = await authApi.qrCreate();

    expect(view).toEqual({ ticketId: 'tk-x', qrPayload: '{"t":"tk-x"}', expiresInSeconds: 120 });
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/qr-tickets');
    expect(captured.current?.init?.method).toBe('POST');
    expect(parseBody(captured.current?.init?.body)).toEqual({});
    // 公开端点：不带 Authorization（契约 API① 公开口径）
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBeNull();
  });

  it('should_getTicketStatusByPath_when_qrPollCalled', async () => {
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope({ status: 'created', token: null });
    }));

    const view = await authApi.qrPoll('tk/1+2');

    expect(view).toEqual({ status: 'created', token: null });
    // ticketId 经 encodeURIComponent 进路径（特殊字符不破坏路径段）
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/qr-tickets/tk%2F1%2B2');
    expect(captured.current?.init?.method ?? 'GET').toBe('GET');
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBeNull();
  });

  it('should_resolveVoidOnEmpty2xx_when_qrCancelCalled', async () => {
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return new Response(null, { status: 200 });
    }));

    await expect(authApi.qrCancel('tk-9')).resolves.toBeUndefined();
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/qr-tickets/tk-9/cancel');
    expect(captured.current?.init?.method).toBe('POST');
  });
});
