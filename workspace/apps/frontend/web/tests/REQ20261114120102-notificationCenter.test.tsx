/**
 * REQ-20261114-120102 AC-11（通知中心抽屉）：开合、收件箱分页、点击标记已读、全部已读、权限门禁。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-filesPage.test.tsx（fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式权限播种 + <AntApp> 包裹 + testTimeout ≥15000ms）。
 *
 * 【表面假定（编号全测试套件连续，汇总见交付说明；待 TL 裁决）】
 *   A-4 抽屉由铃铛点击打开（点击 .ant-badge 容器，事件冒泡语义）；抽屉承载为 antd Drawer，
 *       标题文案「通知中心」；关闭按钮 = .ant-drawer-close。关闭态断言以 .ant-drawer-open 类
 *       消失为准——该类与 open prop 同步翻转、不是动效结束信号，不受 071607 裁决⑪
 *       「jsdom 动效永不结束」影响（⑪ 禁的是等 Modal/Drawer 动效完成信号）。
 *   A-5 收件箱分页：GET /admin/v1/system/messages 响应 {records,total}（CrudTable 分页口径）；
 *       翻页请求携带 page 参数；antd Pagination 承载（.ant-pagination-item-2）；pageSize 默认值
 *       不假设（第二页行渲染不做断言，仅断言 page=2 请求参数）。
 *   A-6 已读交互：点击收件箱条目标题文本节点触发单条已读（PUT {id}/read）；「全部已读」按钮
 *       文案匹配 /全部已读/；闭环断言 = PUT 调用 + 铃铛徽标未读数收敛（本地递减与重拉
 *       unread-count 两策略同收敛，mock 侧服务端未读源值随 PUT 同步变化）；列表是否 reload 不假设。
 *   A-7 权限门禁（三段式播种）：铃铛/收件箱可见 = system:message:list；
 *       已读动作（单条/全部）= system:message:read，缺失时点击条目不触发 PUT（fail-closed）。
 *   A-2 unread-count 响应 data 为裸数字（Result<number>）。
 *   A-16 AdminMessageView wire 形状假定：id/senderId/title/content/isRead/readAt/createdAt/version
 *       （camelCase、id 字符串、时间 ISO-8601，对齐 Admin*View 惯例）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { NotificationBell } from '../src/components/NotificationBell';
import { useAuthStore, useMeStore, useNotifyStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

const PERM_LIST_ONLY = ['system:message:list'];
const PERM_LIST_AND_READ = ['system:message:list', 'system:message:read'];

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

const listCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === 'GET' && r.url.pathname.includes('/messages') && !r.url.pathname.includes('/unread-count'));

const lastListQuery = (fetchMock: ReturnType<typeof vi.fn>) =>
  listCallsOf(fetchMock).at(-1)?.url.searchParams ?? null;

const singleReadCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === 'PUT' && /\/messages\/\d+\/read$/.test(r.url.pathname));

const readAllCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.method === 'PUT' && r.url.pathname.includes('/messages/read-all'));

// ————— 收件箱 API mock：25 条站内信，page/size 切页；服务端未读源值随 PUT 同步收敛 —————
const setupInboxApi = () => {
  const total = 25;
  const all = Array.from({ length: total }, (_, i) => ({
    id: String(9101 + i),
    senderId: '1002',
    title: `站内信-${9101 + i}`,
    content: `<p>内容-${9101 + i}</p>`,
    isRead: false,
    readAt: null,
    createdAt: '2026-11-14T08:00:00Z',
    version: '0',
  }));
  const state = { serverUnread: 2 };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (r.method === 'GET' && p.includes('/messages/unread-count')) {
      return envelope(state.serverUnread);
    }
    if (r.method === 'GET' && p.includes('/messages')) {
      const page = Number(r.url.searchParams.get('page') ?? '1');
      const size = Number(r.url.searchParams.get('size') ?? '10');
      return envelope({ records: all.slice((page - 1) * size, page * size), total });
    }
    if (r.method === 'PUT' && p.includes('/messages/read-all')) {
      state.serverUnread = 0;
      return envelope(null);
    }
    if (r.method === 'PUT' && /\/messages\/\d+\/read$/.test(p)) {
      state.serverUnread = Math.max(0, state.serverUnread - 1);
      return envelope(null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

// ————— 装配 —————
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

const badgeText = () => document.querySelector('.ant-badge')?.textContent ?? '';

const renderBell = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupInboxApi();
  render(<AntApp><HashRouter><NotificationBell /></HashRouter></AntApp>);
  return api;
};

/** 等铃铛兜底拉取就绪后点击铃铛打开抽屉（A-4）。 */
const openNotificationCenter = async (permissions: string[]) => {
  const api = await renderBell(permissions);
  await waitFor(() => expect(badgeText()).toContain('2'));
  fireEvent.click(document.querySelector('.ant-badge') as HTMLElement);
  expect(await screen.findByText('通知中心')).toBeTruthy();
  return api;
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useNotifyStore.setState({ unreadCount: 0 });
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
  useNotifyStore.setState({ unreadCount: 0 });
});

describe('REQ20261114120102 通知中心抽屉（AC-11）', () => {
  it('should_open_notification_center_with_first_page_when_bell_clicked', async () => {
    const { fetchMock } = await openNotificationCenter(PERM_LIST_AND_READ);

    // 开抽屉即拉收件箱首页（分页参数 page=1，CrudTable 口径）
    await waitFor(() => {
      const query = lastListQuery(fetchMock);
      expect(query?.get('page')).toBe('1');
    });
    expect(await screen.findByText('站内信-9101')).toBeTruthy();
  });

  it('should_request_next_page_when_second_pagination_item_clicked', async () => {
    const { fetchMock } = await openNotificationCenter(PERM_LIST_AND_READ);
    await screen.findByText('站内信-9101');

    const page2 = document.querySelector('.ant-pagination-item-2');
    expect(page2).toBeTruthy();
    fireEvent.click(page2 as HTMLElement);

    // 翻页携带 page=2（A-5：size 默认值不假设，第二页行渲染不断言）
    await waitFor(() => {
      expect(lastListQuery(fetchMock)?.get('page')).toBe('2');
    });
  });

  it('should_mark_single_message_read_and_decrement_badge_when_row_clicked', async () => {
    const { fetchMock } = await openNotificationCenter(PERM_LIST_AND_READ);
    await screen.findByText('站内信-9101');

    // 点击条目（标题文本节点）标记已读：PUT /messages/9101/read + 徽标 2→1（收敛口径 A-6）
    fireEvent.click(await screen.findByText('站内信-9101'));

    await waitFor(() => {
      const puts = singleReadCallsOf(fetchMock);
      expect(puts).toHaveLength(1);
      expect(puts[0]?.url.pathname).toContain('/messages/9101/read');
    });
    await waitFor(() => expect(badgeText()).toContain('1'));
  });

  it('should_mark_all_read_and_clear_badge_when_mark_all_read_clicked', async () => {
    const { fetchMock } = await openNotificationCenter(PERM_LIST_AND_READ);

    fireEvent.click(await screen.findByText(/全部已读/));

    await waitFor(() => {
      expect(readAllCallsOf(fetchMock)).toHaveLength(1);
    });
    // 已读闭环（AC-11×AC-10）：全部已读后铃铛徽标未读清零（收敛口径：本地清零/重拉源值同收敛）
    await waitFor(() => {
      expect(/\d/.test(badgeText())).toBe(false);
    });
  });

  it('should_close_drawer_when_close_button_clicked', async () => {
    await openNotificationCenter(PERM_LIST_AND_READ);

    const closeBtn = document.querySelector('.ant-drawer-close');
    expect(closeBtn).toBeTruthy();
    fireEvent.click(closeBtn as HTMLElement);

    // 关闭态断言：.ant-drawer-open 与 open prop 同步翻转（非动效信号，A-4）
    await waitFor(() => {
      expect(document.querySelector('.ant-drawer-open')).toBeNull();
    });
  });

  it('should_not_trigger_read_actions_when_message_read_permission_missing', async () => {
    const { fetchMock } = await openNotificationCenter(PERM_LIST_ONLY); // 仅 list 权限
    await screen.findByText('站内信-9101');

    // fail-closed：无 system:message:read → 点击条目不发已读请求，全部已读入口不渲染（A-7）
    fireEvent.click(await screen.findByText('站内信-9101'));
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(singleReadCallsOf(fetchMock)).toHaveLength(0);
    expect(readAllCallsOf(fetchMock)).toHaveLength(0);
    expect(screen.queryByText(/全部已读/)).toBeNull();
  });
});
