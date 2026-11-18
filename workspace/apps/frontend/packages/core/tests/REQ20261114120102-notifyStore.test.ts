/** @vitest-environment jsdom */
/**
 * REQ-20261114-120102 AC-10（core 通知 store）：未读数兜底拉取 + WS message:new 实时 +1 + 已读递减/清零。
 *
 * 装配照 packages/core/tests/REQ20261114120101-useDict.test.ts（jsdom docblock + Result 信封 +
 * stubGlobal fetch）与 web/tests/meStore.test.ts（zustand store 直测 getState() 口径）。
 *
 * 【表面假定（编号全测试套件连续，汇总见交付说明；待 TL 裁决）】
 *   A-1 通知 store 由 core 根出口导出，命名 useNotifyStore（对齐 useAuthStore/useMeStore 惯例）；
 *       core 测试端经 ../src（index）导入，对内部文件布局不敏感。
 *       动作面：refreshUnread() / applyMessageNew(frame) / markRead(id) / markAllRead()；
 *       状态字段：unreadCount: number。
 *   A-2 unread-count 响应 data 为裸数字（Result<number>）。
 *   A-21 WS message:new 帧按契约 {type,messageId,title} 原样作为 applyMessageNew 入参。
 *
 * 【未读数收敛口径】已读动作后的未读数，「store 本地递减」与「重拉 unread-count」两种实现策略
 * 均可——mock 侧把服务端未读数做成有状态源（PUT 已读后源值同步变化），两种策略在断言处收敛。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useNotifyStore } from '../src';

vi.setConfig({ testTimeout: 15000 });

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

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, extraPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === method && r.url.pathname.includes(extraPart));

/** 单条已读请求（PUT /admin/v1/system/messages/{id}/read；read-all 以字面量排除） */
const singleReadCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === 'PUT' && /\/messages\/\d+\/read$/.test(r.url.pathname));

/**
 * 有状态通知 API mock（收敛口径见头注）：unread 为服务端未读数源——
 * GET unread-count 回源值；PUT 单条已读源值 -1；PUT read-all 源值清零。
 */
const setupNotifyApi = (initialUnread = 2) => {
  let unread = initialUnread;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (r.method === 'GET' && p.includes('/messages/unread-count')) return envelope(unread);
    if (r.method === 'PUT' && /\/messages\/\d+\/read$/.test(p)) {
      unread = Math.max(0, unread - 1);
      return envelope(null);
    }
    if (r.method === 'PUT' && p.includes('/messages/read-all')) {
      unread = 0;
      return envelope(null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

beforeEach(() => {
  useNotifyStore.setState({ unreadCount: 0 });
});

afterEach(() => {
  vi.unstubAllGlobals();
  useNotifyStore.setState({ unreadCount: 0 });
});

describe('REQ20261114120102 通知 store（AC-10）', () => {
  it('should_fetch_unread_count_from_unread_count_endpoint_when_refresh_unread_called', async () => {
    const { fetchMock } = setupNotifyApi(2);

    // 兜底拉取：GET /admin/v1/system/messages/unread-count（契约 §API）
    await useNotifyStore.getState().refreshUnread();

    expect(useNotifyStore.getState().unreadCount).toBe(2);
    expect(callsOf(fetchMock, 'GET', '/messages/unread-count')).toHaveLength(1);
  });

  it('should_increment_unread_count_when_message_new_frame_applied', () => {
    setupNotifyApi(2);
    useNotifyStore.setState({ unreadCount: 2 });

    // WS message:new 事件帧（契约 §实时推送）原样入 store，未读数实时 +1
    useNotifyStore.getState().applyMessageNew({ type: 'message:new', messageId: '9100', title: '实时消息' });

    expect(useNotifyStore.getState().unreadCount).toBe(3);
  });

  it('should_decrement_unread_count_when_single_message_marked_read', async () => {
    const { fetchMock } = setupNotifyApi(2);
    useNotifyStore.setState({ unreadCount: 2 });

    await useNotifyStore.getState().markRead('9101');

    // 收敛口径：本地递减与重拉源值（mock 已置 1）两种策略断言同值
    expect(useNotifyStore.getState().unreadCount).toBe(1);
    const puts = singleReadCallsOf(fetchMock);
    expect(puts).toHaveLength(1);
    expect(puts[0]?.url.pathname).toContain('/messages/9101/read');
  });

  it('should_clear_unread_count_when_mark_all_read_called', async () => {
    const { fetchMock } = setupNotifyApi(2);
    useNotifyStore.setState({ unreadCount: 2 });

    await useNotifyStore.getState().markAllRead();

    expect(useNotifyStore.getState().unreadCount).toBe(0);
    expect(callsOf(fetchMock, 'PUT', '/messages/read-all')).toHaveLength(1);
  });
});
