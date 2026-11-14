/**
 * REQ-20261114-120102 AC-13（WS 客户端）：握手带 token、message:new 帧分发、断线指数退避重连、
 * 主动关闭不重连、重连后链路存活。
 *
 * 环境口径：web vitest 以 *.test.tsx → jsdom（委派单「jsdom 无真 WebSocket」——jsdom 无原生
 * WebSocket，测试以 vi.stubGlobal('WebSocket', MockWebSocket) 注入可注入 stub，A-9）。
 *
 * 【表面假定（编号全测试套件连续，汇总见交付说明；待 TL 裁决）】
 *   A-8 WS 客户端归 web 端（契约 §变更清单将「WS 客户端（重连退避）」列于 web 新增组，未指明
 *       core/web → 假定 web），模块 web/src/ws/notify-client.ts 命名导出工厂 createNotifyClient()：
 *       { connect(): void; close(): void; on(type, handler): void }；token 取自 useAuthStore
 *       会话（对齐 api client 鉴权取值惯例）。
 *   A-9 注入点：客户端默认使用全局 WebSocket（构造 new WebSocket(url, protocols?)），测试以
 *       stubGlobal 注入 MockWebSocket；实现若要求显式注入工厂参数，注入点形态提请 TL 裁决。
 *   A-10 握手 token 载体未契约化（query/子协议均可）：宽松断言「连接 URL 含 /ws/system/notify
 *       且 token 出现在 URL 或 protocols 之一」。
 *   A-11 指数退避基值/抖动/上限未契约化：断言「重连调度严格递增且相邻比值≈2（1.5–2.6 容差）」；
 *       调度采样经 setTimeout spy，仅取 [100ms, 10s) 区间（滤除渲染噪声与 30s 心跳类长定时器）。
 *   A-12 主动 close() 视为正常关闭、不重连（惯例口径）；握手被拒（鉴权失败）是否退避重试未契约化
 *       → 不做断言。异常断线以服务端 close(1006) 模拟。
 *   A-13 心跳 30s 为服务端语义（确认记录④），客户端是否需应用层应答未契约化 → 客户端侧不断言。
 *   A-21 事件帧 {type:"message:new", messageId, title}（契约 §实时推送）按原样分发给 on 处理器。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createNotifyClient } from '../src/ws/notify-client';
import { useAuthStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

type CloseEventLike = { code?: number; reason?: string };
type Handler = (ev: unknown) => void;

/** 可注入 WebSocket stub：on* 与 addEventListener 双兼容，server* 方法模拟服务端行为。 */
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static reset() { MockWebSocket.instances = []; }

  url: string;
  protocols?: string | string[];
  readyState = 0; // CONNECTING
  sent: string[] = [];
  onopen: Handler | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: CloseEventLike) => void) | null = null;
  onerror: Handler | null = null;

  private listeners = new Map<string, Handler[]>();

  constructor(url: string | URL, protocols?: string | string[]) {
    this.url = String(url);
    this.protocols = protocols;
    MockWebSocket.instances.push(this);
  }

  addEventListener(type: string, handler: Handler) {
    const list = this.listeners.get(type) ?? [];
    list.push(handler);
    this.listeners.set(type, list);
  }

  removeEventListener(type: string, handler: Handler) {
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((h) => h !== handler));
  }

  send(data: unknown) { this.sent.push(String(data)); }
  close() { this.readyState = 3; }

  serverOpen() {
    this.readyState = 1;
    this.dispatch('open', {});
  }

  serverMessage(payload: string) {
    this.dispatch('message', { data: payload });
  }

  serverClose(code: number) {
    this.readyState = 3;
    this.dispatch('close', { code });
  }

  private dispatch(type: 'open' | 'message' | 'close' | 'error', ev: unknown) {
    if (type === 'open') this.onopen?.(ev);
    else if (type === 'message') this.onmessage?.(ev as { data: unknown });
    else if (type === 'close') this.onclose?.(ev as CloseEventLike);
    else this.onerror?.(ev);
    for (const handler of this.listeners.get(type) ?? []) handler(ev);
  }
}

// ————— 装配 —————
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

const setupClient = () => {
  vi.stubGlobal('WebSocket', MockWebSocket);
  seedSession();
  return { client: createNotifyClient() };
};

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** 轮询至条件成立（真定时器，供非 fake-timer 用例等待异步 connect 稳定）。 */
const until = async (cond: () => boolean, timeoutMs = 3000) => {
  const start = Date.now();
  while (!cond()) {
    if (Date.now() - start > timeoutMs) throw new Error('condition not met within timeout');
    await flush();
  }
};

/** 重连调度采样（A-11）：仅取 [100ms, 10s) 区间的 setTimeout 延时，滤除渲染噪声与 30s 心跳类长定时器。 */
type TimerSpy = { mock: { calls: unknown[][] } };
const reconnectDelaysOf = (setTimeoutSpy: TimerSpy) =>
  (setTimeoutSpy.mock.calls as unknown as [Handler, number?][]).map((call) => call[1])
    .filter((d): d is number => typeof d === 'number' && d >= 100 && d < 10000);

beforeEach(() => {
  MockWebSocket.reset();
  useAuthStore.getState().clear();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

afterEach(() => {
  // round0 复核·修复 1：先还原全部 mock（含对 globalThis.setTimeout 的探针）再切回真实定时器——
  // 反序会让 useRealTimers 把被 spy 破坏的全局 setTimeout 留成 undefined，jsdom storage.clear()
  // 内部裸调 setTimeout 即抛 ReferenceError（用例体本身可通过，崩在用例间清理）
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  MockWebSocket.reset();
  useAuthStore.getState().clear();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe('REQ20261114120102 WS 客户端（AC-13）', () => {
  it('should_pass_token_in_handshake_when_connect_called', async () => {
    const { client } = setupClient();

    client.connect();
    await until(() => MockWebSocket.instances.length === 1);

    // 握手端点 + token 载体（宽松断言：URL 或子协议，A-10）
    const sock = MockWebSocket.instances[0];
    expect(sock.url).toContain('/ws/system/notify');
    const carriesToken = sock.url.includes('token-1')
      || JSON.stringify(sock.protocols ?? '').includes('token-1');
    expect(carriesToken).toBe(true);
  });

  it('should_dispatch_message_new_event_when_frame_received', async () => {
    const { client } = setupClient();
    const handler = vi.fn();
    client.on('message:new', handler);

    client.connect();
    await until(() => MockWebSocket.instances.length === 1);
    const sock = MockWebSocket.instances[0];
    sock.serverOpen();
    sock.serverMessage(JSON.stringify({ type: 'message:new', messageId: '9001', title: '站内信标题' }));

    // 事件帧按契约结构分发（A-21）
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler.mock.calls[0]?.[0]).toMatchObject({ type: 'message:new', messageId: '9001', title: '站内信标题' });
  });

  it('should_reconnect_with_exponential_backoff_when_connection_dropped_abnormally', async () => {
    vi.useFakeTimers();
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');
    const { client } = setupClient();
    const handler = vi.fn();
    client.on('message:new', handler);

    client.connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(MockWebSocket.instances).toHaveLength(1);

    // 异常断线（服务端 close 1006，非主动关闭）
    MockWebSocket.instances[0].serverOpen();
    MockWebSocket.instances[0].serverClose(1006);
    await vi.advanceTimersByTimeAsync(0);

    expect(reconnectDelaysOf(setTimeoutSpy).length).toBeGreaterThanOrEqual(1);
    const firstDelay = reconnectDelaysOf(setTimeoutSpy)[0];

    // 第一次重连到点：新连接建立
    await vi.advanceTimersByTimeAsync(firstDelay);
    expect(MockWebSocket.instances).toHaveLength(2);

    // 再次异常断线：第二次调度更晚且近似翻倍（指数语义，A-11）
    MockWebSocket.instances[1].serverOpen();
    MockWebSocket.instances[1].serverClose(1006);
    await vi.advanceTimersByTimeAsync(0);

    const delays = reconnectDelaysOf(setTimeoutSpy);
    expect(delays.length).toBeGreaterThanOrEqual(2);
    expect(delays[1]).toBeGreaterThan(delays[0]);
    expect(delays[1]).toBeGreaterThanOrEqual(delays[0] * 1.5);
    expect(delays[1]).toBeLessThanOrEqual(delays[0] * 2.6);

    // 重连成功（AC-13「自动重连成功」）：新 socket 建连后帧分发链路存活
    await vi.advanceTimersByTimeAsync(delays[1]);
    expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(3);
    MockWebSocket.instances[2].serverOpen();
    MockWebSocket.instances[2].serverMessage(JSON.stringify({ type: 'message:new', messageId: '9400', title: '重连后' }));
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('should_not_reconnect_when_client_closed_normally', async () => {
    vi.useFakeTimers();
    const { client } = setupClient();

    client.connect();
    await vi.advanceTimersByTimeAsync(0);
    MockWebSocket.instances[0].serverOpen();

    // 主动关闭（A-12）：不调度重连
    client.close();
    await vi.advanceTimersByTimeAsync(60000);

    expect(MockWebSocket.instances).toHaveLength(1);
  });
});
