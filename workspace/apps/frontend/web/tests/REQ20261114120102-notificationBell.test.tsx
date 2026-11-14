/**
 * REQ-20261114-120102 AC-10（Header 铃铛）+ AC-13 兜底：铃铛挂载兜底拉取 unread-count、
 * WS message:new 实时 +1（store 事件面 + App 装配级端到端一条）、WS 不可用仍兜底拉取。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-filesPage.test.tsx（fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式权限播种 + <AntApp> 包裹 + testTimeout ≥15000ms）与
 * web/tests/REQ20261114120101-nav.test.tsx（App 整棵装配：seedSession + HashRouter 上下文）。
 *
 * 【表面假定（编号全测试套件连续，汇总见交付说明；待 TL 裁决）】
 *   A-3 铃铛组件 web/src/components/NotificationBell.tsx 命名导出 NotificationBell，零必填 props；
 *       挂载即触发未读数兜底拉取（「页面加载拉取兜底」的可观测等价点）；未读徽标以 antd Badge
 *       count 模式承载（count>0 显示数字徽标，红点语义由徽标红色承载；若实现为 dot 模式则断言
 *       需按裁决调整）。
 *   A-7 铃铛/通知中心可见性门禁 = system:message:list（三段式播种，round1 裁决⑤口径）。
 *   A-19 App 级集成前提：登录态 App 挂载即建立 WS 连接并接线 message:new → 通知 store → 铃铛。
 *   A-9 WS 注入点：客户端默认读全局 WebSocket，测试以 vi.stubGlobal('WebSocket', MockWebSocket)
 *       注入（jsdom 无真 WebSocket；显式工厂注入点未契约化，若实现要求显式注入提请裁决）。
 *   A-2 unread-count 响应 data 为裸数字（Result<number>）。
 *
 * 【未读数收敛口径】message:new 后的徽标数，「store 本地 +1」与「重拉 unread-count」两种实现
 * 策略均可——mock 侧提供 bumpUnread() 推进服务端未读源值后再派发事件，两种策略在断言处收敛。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { NotificationBell } from '../src/components/NotificationBell';
import { useAuthStore, useMeStore, useNotifyStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// React 18 act 环境（本文件直呼 act 派发 store 事件；useDict 范本口径）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const PERM_MESSAGE_LIST = ['system:message:list'];

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

const unreadCountCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/messages/unread-count'));

// ————— 可注入 WebSocket stub（jsdom 无真 WebSocket；连接永不建立即模拟「WS 不可用/未连接」） —————
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static reset() { MockWebSocket.instances = []; }

  url: string;
  protocols?: string | string[];
  onopen: ((ev?: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev?: unknown) => void) | null = null;
  onerror: ((ev?: unknown) => void) | null = null;

  constructor(url: string | URL, protocols?: string | string[]) {
    this.url = String(url);
    this.protocols = protocols;
    MockWebSocket.instances.push(this);
  }

  send() {}
  close() {}

  // 测试驱动：server* 语义=模拟服务端行为；默认不调用即连接悬挂（CONNECTING）
  serverOpen() { this.onopen?.({}); }
  serverMessage(payload: string) { this.onmessage?.({ data: payload }); }
}

// ————— 装配 —————
const stubBrowserGaps = () => {
  // min-width 断点置 true：App 整棵装配用例按桌面布局渲染（nav.test 装配口径）
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

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
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

/** 有状态 unread-count 源（收敛口径见头注）：bumpUnread 模拟「后端已计入新消息」。 */
const setupBellApi = () => {
  const state = { serverUnread: 2 };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.method === 'GET' && r.url.pathname.includes('/messages/unread-count')) {
      return envelope(state.serverUnread);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    fetchMock,
    bumpUnread: () => { state.serverUnread += 1; },
  };
};

// 按铃铛收敛定位（round0 复核·修复 2-2）：App 装配下首个 .ant-badge 是导航徽标（种子 activeCount=6，
// Sider 先渲染），裸查 .ant-badge 会断言到错误徽标；铃铛触发器带 aria-label="通知中心"
const badgeText = () => document.querySelector('[aria-label="通知中心"] .ant-badge')?.textContent ?? '';

const renderBell = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupBellApi();
  // HashRouter 包裹为保险：若铃铛内部消费 router hooks 不致崩（独立渲染，A-3）
  render(<AntApp><HashRouter><NotificationBell /></HashRouter></AntApp>);
  return api;
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

/** App 整棵装配（nav.test 口径）+ 通知 API + WS stub：AC-10 实时链路端到端。 */
const renderAppWithNotify = async () => {
  seedSession();
  const state = { serverUnread: 2 };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    // round0 复核·修复 2-1：必须 endsWith('/me')——/admin/v1/system/messages/unread-count
    // 含子串 '/system/me'，裸 includes 会把未读请求劫持回 me 载荷（任何符合契约的实现都命中）
    if (r.method === 'GET' && p.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERM_MESSAGE_LIST, menus: [] });
    }
    if (r.method === 'GET' && p.includes('/messages/unread-count')) {
      return envelope(state.serverUnread);
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  vi.stubGlobal('WebSocket', MockWebSocket);
  window.location.hash = '#/';
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return {
    fetchMock,
    bumpUnread: () => { state.serverUnread += 1; },
  };
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useNotifyStore.setState({ unreadCount: 0 });
  MockWebSocket.reset();
  stubBrowserGaps();
  // 保险桩：通知面组件若自行建连不致因 jsdom 缺 WebSocket 而崩（A-9）
  vi.stubGlobal('WebSocket', MockWebSocket);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useNotifyStore.setState({ unreadCount: 0 });
  MockWebSocket.reset();
});

describe('REQ20261114120102 Header 铃铛（AC-10）', () => {
  it('should_fetch_unread_count_on_mount_and_render_badge_when_bell_renders', async () => {
    const { fetchMock } = await renderBell(PERM_MESSAGE_LIST);

    // 页面加载兜底：铃铛挂载即拉取 unread-count（A-3），徽标呈现未读数
    await waitFor(() => {
      expect(unreadCountCallsOf(fetchMock).length).toBeGreaterThanOrEqual(1);
    });
    await waitFor(() => {
      expect(badgeText()).toContain('2');
    });
  });

  it('should_update_badge_realtime_when_message_new_applied_to_notify_store', async () => {
    const { bumpUnread } = await renderBell(PERM_MESSAGE_LIST);
    await waitFor(() => expect(badgeText()).toContain('2'));

    // message:new 到达（AC-10 实时 +1）：先推进服务端源值再派发（收敛口径，本地+1/重拉两策略同收敛）
    bumpUnread();
    act(() => {
      useNotifyStore.getState().applyMessageNew({ type: 'message:new', messageId: '9200', title: '实时推送' });
    });

    await waitFor(() => expect(badgeText()).toContain('3'));
  });
});

describe('REQ20261114120102 Header 铃铛——兜底降级（AC-13 兜底口径）', () => {
  it('should_fallback_fetch_unread_count_when_websocket_never_opens', async () => {
    // WebSocket 桩永不 serverOpen：WS 通道不可用/悬挂，兜底拉取仍必须发生
    const { fetchMock } = await renderBell(PERM_MESSAGE_LIST);

    await waitFor(() => {
      expect(unreadCountCallsOf(fetchMock).length).toBeGreaterThanOrEqual(1);
    });
    await waitFor(() => {
      expect(badgeText()).toContain('2');
    });
  });
});

describe('REQ20261114120102 App 装配级 WS 接线（AC-10 实时链路端到端）', () => {
  it('should_update_badge_when_ws_frame_received_in_assembled_app', async () => {
    const { fetchMock, bumpUnread } = await renderAppWithNotify();

    // 页面加载兜底拉取（App 级）
    await waitFor(() => {
      expect(unreadCountCallsOf(fetchMock).length).toBeGreaterThanOrEqual(1);
    });
    await waitFor(() => expect(badgeText()).toContain('2'));

    // 登录态挂载即建连（A-19）：WS 帧派发 → store → 徽标 +1
    const socket = MockWebSocket.instances.at(-1);
    expect(socket).toBeTruthy();
    socket!.serverOpen();
    bumpUnread();
    socket!.serverMessage(JSON.stringify({ type: 'message:new', messageId: '9300', title: 'WS 实时' }));

    await waitFor(() => expect(badgeText()).toContain('3'));
  });
});
