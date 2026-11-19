/**
 * REQ-20261119-011524（web 首页——平台概览统计与 widget 改版）AC-4/AC-5/契约⑤⑥：
 * - 平台概览六统计卡：数字来自 summary（wire Long→string 解析为 number）；summary 不可用 → '—' 降级；
 * - echarts 数据源切换：经 useDashboardCharts(stats) 既有预留位直测（对齐 chartOptionFactory/chart 测试模式），
 *   无 summary → 保持本地 release 数据源降级；
 * - 已发布公告 widget：noticeList(1,5) + 客户端过滤 PUBLISHED 取 5 条；无 PUBLISHED → 空态；
 *   无 system:notice:list 权限 → widget 不渲染且不发公告请求（入口型 widget 权限显隐，契约①）；
 * - 快捷入口：Auth 原语（me.permissions）按各域 list 权限显隐，fail-closed；
 * - api 层：GET /admin/v1/system/dashboard/summary 路径 + wire 数字容错解析；
 * - hook：15min stale 标记 + 手动刷新复位（契约⑥）。
 *
 * 装配照既有范本 web/tests/REQ20261114120102-noticesPage.test.tsx（fetch stubGlobal 弱匹配路由 +
 * useMeStore 播种 + <AntApp> 包裹 + testTimeout ≥15000ms）与 dateTimezone.test.tsx 的 renderHook 口径。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { Dashboard } from '../src/pages/Dashboard';
import { useDashboardCharts } from '../src/hooks/useDashboardCharts';
import { useDashboardSummary } from '../src/hooks/useDashboardSummary';
import { systemApi, useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// ————— fixtures（wire 形态：契约 Long→string，数字字段为字符串；降级字段由后端给 0/空） —————

/** 近 14 日登录趋势（14 桶，合计 371：20+21+…+33）。 */
const TREND_COUNTS = [20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33];
const LOGINS_14D_TOTAL = TREND_COUNTS.reduce((n, c) => n + c, 0);

const SUMMARY_WIRE = {
  users: '201',
  roles: '34',
  tenants: '87',
  onlineUsers: '63',
  unreadMessages: '29',
  loginTrend: TREND_COUNTS.map((count, i) => ({
    date: `2026-11-${String(6 + i).padStart(2, '0')}`,
    count: String(count),
  })),
  auditTopActions: [
    { action: 'user.login', count: '120' },
    { action: 'notice.read', count: '64' },
    { action: 'file.download', count: '32' },
    { action: 'role.update', count: '16' },
    { action: 'tenant.create', count: '8' },
  ],
};

type NoticeRow = {
  id: string;
  title: string;
  content: string;
  status: string;
  publishAt: string | null;
  createdAt: string;
  version: string;
};

const noticeRow = (id: string, title: string, status: string, publishAt: string | null): NoticeRow => ({
  id, title, content: `<p>${title}</p>`, status, publishAt,
  createdAt: '2026-11-01T08:00:00Z', version: '0',
});

/** 混合状态 fixture：7 条 PUBLISHED（超出 5 条上限）+ 草稿 + 已下线。 */
const MIXED_NOTICES: NoticeRow[] = [
  noticeRow('6001', '停机维护公告', 'PUBLISHED', '2026-11-19T09:00:00Z'),
  noticeRow('6002', '新版本发布说明', 'PUBLISHED', '2026-11-18T09:00:00Z'),
  noticeRow('6003', '安全补丁升级通知', 'PUBLISHED', '2026-11-17T09:00:00Z'),
  noticeRow('6004', '平台计费策略调整', 'PUBLISHED', '2026-11-16T09:00:00Z'),
  noticeRow('6005', '系统巡检完成通报', 'PUBLISHED', '2026-11-15T09:00:00Z'),
  noticeRow('6006', '年度合规审计启动', 'PUBLISHED', '2026-11-14T09:00:00Z'),
  noticeRow('6007', '数据备份策略更新', 'PUBLISHED', '2026-11-13T09:00:00Z'),
  noticeRow('6008', '内部草稿（勿发）', 'DRAFT', null),
  noticeRow('6009', '旧版下线公告', 'OFFLINE', '2026-11-10T09:00:00Z'),
];

// ————— 通用装配 —————

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase() };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase() };
};

const callsTo = (fetchMock: ReturnType<typeof vi.fn>, pathPart: string, method = 'GET') =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

/** seedMe：播种 me 画像（Auth 原语 me.permissions 的数据来源；meStore.test 范本口径）。 */
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

/** 概览/公告域 API 路由 mock（summary 失败/空 data/正常三态；notices 按fixture 返回）。 */
const setupApi = (options: { summary?: 'ok' | 'fail' | 'null-data'; notices?: NoticeRow[] } = {}) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p.includes('/dashboard/summary')) {
      if (options.summary === 'fail') return failureEnvelope('SUMMARY_DOWN', '下游统计服务不可用');
      return envelope(options.summary === 'null-data' ? null : SUMMARY_WIRE);
    }
    if (p.includes('/system/notices')) {
      const rows = options.notices ?? [];
      return envelope({ records: rows, total: rows.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

const stubBrowserGaps = () => {
  // antd 响应式断点依赖 matchMedia/ResizeObserver（jsdom 未实现，nav.test 同口径装配桩）
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

const renderDashboard = () =>
  render(
    <AntApp>
      <MemoryRouter initialEntries={['/']}>
        <Dashboard />
      </MemoryRouter>
    </AntApp>,
  );

/** 定位平台概览卡（标题锚点 → 最近的 ant-card 根）。 */
const overviewCard = () => screen.getByText('平台概览').closest('.ant-card') as HTMLElement;

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-4：平台概览六卡 —————
describe('REQ20261119011524 平台概览六卡（AC-4）', () => {
  it('should_render_six_overview_stats_when_summary_loaded', async () => {
    await seedMe(['system:notice:list']);
    const fetchMock = setupApi({ summary: 'ok', notices: MIXED_NOTICES });
    renderDashboard();

    const card = overviewCard();
    // 六个数字全部来自 summary（wire string 解析为 number）；371 = 近 14 日登录合计
    expect(await within(card).findByText('201')).toBeTruthy();
    expect(within(card).getByText('34')).toBeTruthy();
    expect(within(card).getByText('87')).toBeTruthy();
    expect(within(card).getByText('63')).toBeTruthy();
    expect(within(card).getByText('29')).toBeTruthy();
    expect(within(card).getByText(String(LOGINS_14D_TOTAL))).toBeTruthy();
    // 六个统计标签成对渲染
    expect(within(card).getByText('用户总数')).toBeTruthy();
    expect(within(card).getByText('角色总数')).toBeTruthy();
    expect(within(card).getByText('租户总数')).toBeTruthy();
    expect(within(card).getByText('在线用户')).toBeTruthy();
    expect(within(card).getByText('未读消息')).toBeTruthy();
    expect(within(card).getByText('近 14 日登录')).toBeTruthy();
    // api 层路径：GET /admin/v1/system/dashboard/summary
    expect(callsTo(fetchMock, '/admin/v1/system/dashboard/summary')).toHaveLength(1);
  });

  it('should_show_dash_placeholder_when_summary_request_fails', async () => {
    await seedMe([]);
    setupApi({ summary: 'fail' });
    renderDashboard();

    // summary 请求失败 → 六卡全部按降级显示 '—'（数字型 widget 对全员可见，契约④）
    await waitFor(() => {
      expect(within(overviewCard()).getAllByText('—')).toHaveLength(6);
    });
  });
});

// ————— AC-4：echarts 数据源切换（useDashboardCharts stats props 直测，对齐 chartOptionFactory 测试模式） —————
describe('REQ20261119011524 图表数据源切换（AC-4）', () => {
  it('should_build_trend_option_from_backend_login_trend_when_stats_given', () => {
    const stats = {
      trend: { labels: ['11/6', '11/7', '11/8'], values: [20, 21, 22] },
      distribution: { items: [{ name: 'user.login', value: 120 }] },
    };
    const { result } = renderHook(() => useDashboardCharts(stats));

    const trend = result.current.trend as Record<string, unknown>;
    expect((trend.series as Array<{ data: number[] }>)[0].data).toEqual([20, 21, 22]);
    expect(((trend.xAxis as { data: string[] }).data)).toEqual(['11/6', '11/7', '11/8']);
  });

  it('should_build_distribution_option_from_backend_audit_actions_when_stats_given', () => {
    const stats = {
      trend: { labels: ['11/6'], values: [20] },
      distribution: {
        items: [
          { name: 'user.login', value: 120 },
          { name: 'notice.read', value: 64 },
        ],
      },
    };
    const { result } = renderHook(() => useDashboardCharts(stats));

    const distribution = result.current.distribution as Record<string, unknown>;
    const pie = (distribution.series as Array<{ type: string; data: Array<{ name: string; value: number }> }>)
      .find((s) => s.type === 'pie');
    expect(pie?.data.map((d) => d.name)).toEqual(['user.login', 'notice.read']);
    expect(pie?.data.map((d) => d.value)).toEqual([120, 64]);
  });

  it('should_keep_local_release_source_when_no_summary_stats', () => {
    // 无 summary（stats=undefined）→ 保持本地 release 数据源降级（既有预留位口径）
    const { result } = renderHook(() => useDashboardCharts());
    const trend = result.current.trend as Record<string, unknown>;
    expect(((trend.series as Array<{ data: number[] }>)[0]).data).toHaveLength(7); // 本地近 7 日趋势
  });
});

// ————— AC-5：已发布公告 widget —————
describe('REQ20261119011524 已发布公告 widget（AC-5）', () => {
  it('should_filter_published_notices_when_widget_loaded', async () => {
    await seedMe(['system:notice:list']);
    setupApi({ summary: 'ok', notices: MIXED_NOTICES });
    renderDashboard();

    // PUBLISHED 前 5 条可见（标题 + 列表）
    expect(await screen.findByText('停机维护公告')).toBeTruthy();
    expect(screen.getByText('新版本发布说明')).toBeTruthy();
    expect(screen.getByText('安全补丁升级通知')).toBeTruthy();
    expect(screen.getByText('平台计费策略调整')).toBeTruthy();
    expect(screen.getByText('系统巡检完成通报')).toBeTruthy();
    // 第 6 条 PUBLISHED 被截断（客户端过滤后取 5 条）
    expect(screen.queryByText('年度合规审计启动')).toBeNull();
    expect(screen.queryByText('数据备份策略更新')).toBeNull();
    // DRAFT/OFFLINE 被客户端过滤
    expect(screen.queryByText('内部草稿（勿发）')).toBeNull();
    expect(screen.queryByText('旧版下线公告')).toBeNull();
  });

  it('should_show_empty_state_when_no_published_notices', async () => {
    await seedMe(['system:notice:list']);
    setupApi({ summary: 'ok', notices: [MIXED_NOTICES[7]!, MIXED_NOTICES[8]!] }); // 仅 DRAFT/OFFLINE
    renderDashboard();

    expect(await screen.findByText('暂无发布公告')).toBeTruthy();
    expect(screen.queryByText('内部草稿（勿发）')).toBeNull();
  });

  it('should_skip_notices_request_when_notice_permission_missing', async () => {
    await seedMe([]);
    const fetchMock = setupApi({ summary: 'ok', notices: MIXED_NOTICES });
    renderDashboard();

    // summary 正常拉取（数字型 widget 对全员可见）
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/system/dashboard/summary')).toHaveLength(1);
    });
    // 缺 system:notice:list → 公告 widget 不渲染且不发公告请求（fail-closed）
    expect(callsTo(fetchMock, '/system/notices')).toHaveLength(0);
    expect(screen.queryByText('已发布公告')).toBeNull();
    expect(screen.queryByText('停机维护公告')).toBeNull();
  });
});

// ————— AC-4：快捷入口权限显隐（Auth 原语 me.permissions，fail-closed） —————
describe('REQ20261119011524 快捷入口（AC-4）', () => {
  it('should_show_quick_access_entries_when_permissions_held', async () => {
    await seedMe(['system:user:list', 'infra:file:list']);
    setupApi({ summary: 'ok', notices: [] });
    renderDashboard();

    const card = (await screen.findByText('快捷入口')).closest('.ant-card') as HTMLElement;
    expect(within(card).getByText('用户管理')).toBeTruthy();
    expect(within(card).getByText('文件管理')).toBeTruthy();
    // 未持权限的入口不渲染
    expect(within(card).queryByText('租户管理')).toBeNull();
    expect(within(card).queryByText('审计事件')).toBeNull();
  });

  it('should_hide_quick_access_when_permissions_missing', async () => {
    await seedMe([]);
    setupApi({ summary: 'ok', notices: [] });
    renderDashboard();

    // 等待页面完成首帧渲染（平台概览卡始终在场）
    await waitFor(() => {
      expect(screen.getByText('平台概览')).toBeTruthy();
    });
    // 无任何入口权限 → 快捷入口卡整体不渲染（fail-closed）
    expect(screen.queryByText('快捷入口')).toBeNull();
    expect(screen.queryByText('用户管理')).toBeNull();
    expect(screen.queryByText('文件管理')).toBeNull();
  });
});

// ————— 契约⑥：summary hook（15min stale 标记 + 手动刷新） —————
describe('REQ20261119011524 summary hook stale 标记（契约⑥）', () => {
  it('should_mark_stale_after_fifteen_minutes_and_reset_when_refreshed', async () => {
    // 仅冻结 Date + 拦截 interval（setTimeout 保持真实：apiFetch 超时护栏与 flush 等待都依赖它）
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    try {
      vi.setSystemTime(new Date('2026-11-20T09:00:00Z'));
      vi.stubGlobal('fetch', vi.fn(async () => envelope(SUMMARY_WIRE)));
      const { result } = renderHook(() => useDashboardSummary());
      const flush = () => act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
      await flush();
      expect(result.current.data).not.toBeNull();
      expect(result.current.stale).toBe(false);

      // 推进 16 分钟 → stale 巡检翻转（15min stale 标记）
      await act(async () => {
        vi.advanceTimersByTime(16 * 60 * 1000);
        await Promise.resolve();
      });
      expect(result.current.stale).toBe(true);

      // 手动刷新 → 复位为新鲜
      vi.setSystemTime(new Date('2026-11-20T09:20:00Z'));
      await act(async () => {
        void result.current.refresh();
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
      expect(result.current.stale).toBe(false);
      expect(result.current.error).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('should_keep_last_data_and_report_error_when_refresh_fails', async () => {
    const fetchMock = vi.fn(async () => envelope(SUMMARY_WIRE));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useDashboardSummary());
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(result.current.data).not.toBeNull();

    fetchMock.mockImplementation(async () => failureEnvelope('SUMMARY_DOWN', '下游统计服务不可用'));
    await act(async () => {
      void result.current.refresh();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    // 失败保留上次成功数据，错误可见
    expect(result.current.data).not.toBeNull();
    expect(result.current.error).toBeTruthy();
    expect(result.current.stale).toBe(true);
  });
});

// ————— api 层：路径 + wire 数字容错解析 —————
describe('REQ20261119011524 dashboardSummary api（契约①）', () => {
  it('should_parse_wire_string_numbers_when_summary_endpoint_called', async () => {
    const fetchMock = vi.fn(async () => envelope(SUMMARY_WIRE));
    vi.stubGlobal('fetch', fetchMock);

    const summary = await systemApi.dashboardSummary();

    // GET /admin/v1/system/dashboard/summary
    expect(callsTo(fetchMock, '/admin/v1/system/dashboard/summary')).toHaveLength(1);
    expect(summary).not.toBeNull();
    // wire Long→string → 解析为 number
    expect(summary!.users).toBe(201);
    expect(summary!.roles).toBe(34);
    expect(summary!.tenants).toBe(87);
    expect(summary!.onlineUsers).toBe(63);
    expect(summary!.unreadMessages).toBe(29);
    expect(summary!.loginTrend).toHaveLength(14);
    expect(summary!.loginTrend[0]).toEqual({ date: '2026-11-06', count: 20 });
    expect(summary!.loginTrend.every((p) => typeof p.count === 'number')).toBe(true);
    expect(summary!.auditTopActions[0]).toEqual({ action: 'user.login', count: 120 });
    expect(summary!.auditTopActions).toHaveLength(5);
  });

  it('should_tolerate_plain_numbers_and_invalid_values_when_parsing_summary', async () => {
    // 序列化口径演进容错：number 直收；非法字符串/缺失字段按降级 0/空收敛
    vi.stubGlobal('fetch', vi.fn(async () => envelope({
      users: 55,
      roles: 'abc',
      tenants: null,
      loginTrend: [{ date: '2026-11-06', count: '12' }],
    })));

    const summary = await systemApi.dashboardSummary();

    expect(summary!.users).toBe(55);
    expect(summary!.roles).toBe(0);
    expect(summary!.tenants).toBe(0);
    expect(summary!.onlineUsers).toBe(0);
    expect(summary!.unreadMessages).toBe(0);
    expect(summary!.loginTrend).toEqual([{ date: '2026-11-06', count: 12 }]);
    expect(summary!.auditTopActions).toEqual([]);
  });

  it('should_return_null_when_summary_data_missing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope(null)));

    expect(await systemApi.dashboardSummary()).toBeNull();
  });
});
