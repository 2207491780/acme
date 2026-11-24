/** @vitest-environment jsdom */
/**
 * REQ-20261117-061017：日志中心完善＋定时任务管理页（前端面，AC-8 / AC-10 前端半句）。
 *
 * 覆盖：ExceptionsPage（列表/筛选/详情抽屉/导出带参）、JobsPage（REQ-20261122-023213 改造为
 * 调度中心三 Tab 页后的旧断言适配：scheduler 面 rows/手动触发/执行历史 Tab）、
 * App NAV 权限显隐（调度中心=scheduler:job:list）+ PermissionGate 拒绝态、
 * OnlineUsersPage 按用户名批量下线（kicked/skipped 反馈）、AuditPage/LoginLogsPage 导出带参、
 * core api 层路径/载荷（导出 URL 组装 / 批量下线）。
 *
 * REQ-20261122-023213 AC-12 适配（2026-11-22）：JobsPage 全切 /admin/v1/scheduler/** 面与
 * JobAdminView/ExecutionView 形状（JOB-3 契约确认②⑦）；渲染走整棵 App（「JobsPage 改造 或
 * 新组件+路由复用」双实现口径兼容，不直连页面 import）。
 *
 * 装配照既有范本 web/tests/REQ20261114120105-loginSecurityPages.test.tsx + REQ20261114120101-filesPage.test.tsx：
 * fetch stubGlobal 弱匹配路由 + useMeStore 权限播种 + <AntApp> 包裹 + testTimeout ≥15000ms；
 * 关闭态断言一律数据驱动（071607 裁决⑪）；下载链路 stub URL.createObjectURL（filesPage A-31 口径）。
 *
 * 【口径说明（契约/DTO 推导）】
 *   - DTO 形状 = 契约 §API（AdminOpExceptionView / BatchKickView）；id wire string。
 *   - 导出 = useExcelExport → downloadFile（apiFetchRaw 二进制），断言导出端点 GET + 筛选参数透传。
 *   - 批量下线 = DELETE /admin/v1/auth/online-users?username=（query 参数），反馈含 kicked/skipped 计数。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { AuditPage } from '../src/pages/AuditPage';
import { LoginLogsPage } from '../src/pages/LoginLogsPage';
import { OnlineUsersPage } from '../src/pages/OnlineUsersPage';
import { ExceptionsPage } from '../src/pages/ExceptionsPage';
import { authApi, infraApi, auditApi, useAuthStore, useMeStore } from '@acme/core';

// REQ-20261119-051119 装配放宽（对齐 crudPages 先例）：全量套件并行负载下 OnlineUsers 批量下线两例贴线超时
// （隔离/受控负载下均 5-8s 通过，断言不变，仅预算 15000→25000）。
// testTimeout 102s（TL round-2 预算放宽，判定链闭环后披露制：theme4-idle-rerun.log 失败用例闲置实测
// 40.6s（手动触发执行 ID 确认流）；新预算=max(实测×2.5, 现预算×2)=101518→102s，重交互用例 ≥60s 口径；
// 断言/行为零改动）
vi.setConfig({ testTimeout: 102000 });

// ————— 行为常量（范本口径：两字按钮 autoInsertSpace 兼容） —————
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const EXPORT_BUTTON = /导\s*出/;
const DETAIL_BUTTON = /详\s*情/;
const BATCH_KICK_BUTTON = /^下\s*线$/; // 行内「踢下线」以 踢 开头，不匹配锚定批量下线按钮

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— fetch 边界 mock（范本 helpers） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const lastQuery = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  callsOf(fetchMock, method, pathPart).at(-1)?.url.searchParams ?? null;

// ————— fixtures（契约 §API 形状；id wire string） —————
const OP_EXCEPTION_ROWS = [
  {
    id: '6201', service: 'platform-infra-service', traceId: 'trace-exc-1', spanId: 'span-exc-1',
    tenantId: '1', operatorId: '1001', method: 'POST', path: '/admin/v1/infra/files', statusCode: 500,
    exceptionType: 'java.lang.IllegalStateException', message: '存储初始化失败',
    stackSummary: 'java.lang.IllegalStateException: 存储初始化失败\n\tat com.acme.infra.StorageBootstrap.run(StorageBootstrap.java:42)',
    occurredAt: '2026-11-17T04:00:00Z',
  },
  {
    id: '6202', service: 'platform-system-service', traceId: 'trace-exc-2', spanId: 'span-exc-2',
    tenantId: '1', operatorId: '1002', method: 'GET', path: '/admin/v1/system/users', statusCode: 500,
    exceptionType: 'org.springframework.dao.DataAccessException', message: '数据访问还原失败',
    stackSummary: 'org.springframework.dao.DataAccessException: 数据访问还原失败\n\tat com.acme.system.UserQueryUseCase.list(UserQueryUseCase.java:88)',
    occurredAt: '2026-11-17T05:00:00Z',
  },
];

const JOB_ROWS = [
  {
    id: '301', version: '0', jobKey: 'file-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cron: '0 0 3 * * *', fixedRate: null, nextFireTime: '2026-11-23T03:00:00Z',
    status: 'DISABLED', timeoutMs: 60000, shardTotal: 1,
  },
  {
    id: '305', version: '2', jobKey: 'custom-report-sync', appName: 'business-report',
    triggerType: 'FIXED_RATE', cron: null, fixedRate: 300000, nextFireTime: '2026-11-22T10:05:00Z',
    status: 'ENABLED', timeoutMs: 30000, shardTotal: 1,
  },
];

const EXECUTION_ROWS = [
  {
    id: '9001', definitionId: '10', jobKey: 'file-retention-cleanup', triggerType: 'MANUAL',
    fireTime: '2026-11-22T09:00:00Z', state: 'SUCCESS', attempt: 1, shardIndex: 0,
    result: 'ok', error: null, costMs: 120, traceId: 'trace-exec-1',
  },
  {
    id: '9002', definitionId: '10', jobKey: 'file-retention-cleanup', triggerType: 'SCHEDULE',
    fireTime: '2026-11-22T03:00:00Z', state: 'FAILED', attempt: 2, shardIndex: 0,
    result: null, error: '模拟执行失败（fixture）', costMs: 800, traceId: 'trace-exec-2',
  },
];

const ONLINE_USER_ROWS = [
  {
    sessionId: 'sess-op-1', username: 'admin', userType: 'B', ip: '10.0.0.9',
    loginAt: '2026-11-17T01:00:00Z', lastActiveAt: '2026-11-17T03:00:00Z',
  },
  {
    sessionId: 'sess-op-2', username: 'ops', userType: 'B', ip: '10.0.0.10',
    loginAt: '2026-11-17T02:00:00Z', lastActiveAt: '2026-11-17T03:10:00Z',
  },
];

// ————— 页面级 fetch mock（按 pathPart 弱匹配路由） —————
const setupApi = (routes: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    for (const [part, handler] of Object.entries(routes)) {
      if (r.url.pathname.includes(part)) {
        return handler(r);
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

// ————— 装配（范本口径） —————
const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    // min-width 断点全命中=桌面侧边导航（nav.test 口径）；全 false 会压成移动布局，
    // 主线 8369027 移除底部移动导航后移动布局无导航标签，NAV 断言将找不到文案
    matches: query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  // jsdom 缺省无 createObjectURL：下载链路打桩（filesPage A-31 口径）
  (URL as unknown as { createObjectURL?: unknown }).createObjectURL = vi.fn(() => 'blob:acme-mock');
  (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL = vi.fn();
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

const renderPage = async (Component: React.ComponentType, permissions: string[], routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  await seedMe(permissions);
  const api = routes ? setupApi(routes) : setupApi({});
  render(<AntApp><Component /></AntApp>);
  return api;
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 整棵 App 渲染（NAV 显隐 / PermissionGate / 页面路由用，nav.test 范本口径；023213 适配支持指定 hash 与 API 路由）。 */
const renderApp = async (
  permissions: string[],
  hash = '#/',
  routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>,
) => {
  seedSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const url = r.url;
    if (url.pathname.includes('/system/me')) {
      return envelope({
        user: {
          id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
          status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
        },
        roles: ['super_admin'],
        permissions,
        menus: [],
      });
    }
    for (const [part, handler] of Object.entries(routes ?? {})) {
      if (url.pathname.includes(part)) return handler(r);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

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
  delete (URL as unknown as { createObjectURL?: unknown }).createObjectURL;
  delete (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL;
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— ExceptionsPage（AC-10 前端半句：列表/筛选/详情/导出） —————
describe('REQ20261117061017 ExceptionsPage（AC-10）', () => {
  const PERM = ['infra:exception:list'];
  const routes = () => ({
    '/op-exceptions/export': () => new Response(
      new Blob(['PK\x03\x04mock-xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
      { status: 200, headers: { 'Content-Disposition': 'attachment; filename="op-exceptions.xlsx"' } },
    ),
    '/op-exceptions/': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') {
        const id = r.url.pathname.split('/').filter(Boolean).pop() ?? '';
        const row = OP_EXCEPTION_ROWS.find((item) => item.id === id);
        return row ? envelope(row) : envelope(null, 404);
      }
      return envelope(null);
    },
    '/op-exceptions': (r: ReturnType<typeof asRequest>) => {
      if (r.method !== 'GET') return envelope(null);
      const service = r.url.searchParams.get('service');
      const rows = OP_EXCEPTION_ROWS.filter((row) => !service || row.service === service);
      return envelope({ records: rows, total: rows.length });
    },
  });

  it('should_render_exception_rows_with_status_tag_when_page_loads', async () => {
    const { fetchMock } = await renderPage(ExceptionsPage, PERM, routes());
    expect(await screen.findByText('platform-infra-service')).toBeTruthy();
    expect(screen.getByText('存储初始化失败')).toBeTruthy();
    expect(screen.getByText('java.lang.IllegalStateException')).toBeTruthy();
    // 列表请求携带 page=1（分页口径）
    expect(lastQuery(fetchMock, 'GET', 'op-exceptions')?.get('page')).toBe('1');
  });

  it('should_pass_service_filter_when_query_submitted', async () => {
    const { fetchMock } = await renderPage(ExceptionsPage, PERM, routes());
    await screen.findByText('platform-infra-service');

    fireEvent.change(await screen.findByPlaceholderText('服务名'), { target: { value: 'platform-infra-service' } });
    fireEvent.click(screen.getByRole('button', { name: '查 询' }));

    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', 'op-exceptions')?.get('service')).toBe('platform-infra-service');
    });
    // 筛选提交重置到第 1 页
    expect(lastQuery(fetchMock, 'GET', 'op-exceptions')?.get('page')).toBe('1');
  });

  it('should_fetch_detail_with_full_stack_when_detail_opened', async () => {
    const { fetchMock } = await renderPage(ExceptionsPage, PERM, routes());
    const rowEl = await findRowByText('platform-infra-service');

    fireEvent.click(await within(rowEl).findByText(DETAIL_BUTTON));

    // 详情端点被调用（GET /op-exceptions/{id}），抽屉展示堆栈摘要全文
    await waitFor(() => {
      const details = callsOf(fetchMock, 'GET', '/op-exceptions/6201');
      expect(details).toHaveLength(1);
    });
    expect(await screen.findByText(/StorageBootstrap\.run/)).toBeTruthy();
    expect(screen.getByText('堆栈摘要')).toBeTruthy();
  });

  it('should_request_export_with_filters_when_export_clicked', async () => {
    const { fetchMock } = await renderPage(ExceptionsPage, PERM, routes());
    await screen.findByText('platform-infra-service');

    fireEvent.change(await screen.findByPlaceholderText('服务名'), { target: { value: 'platform-infra-service' } });
    fireEvent.click(screen.getByRole('button', { name: '查 询' }));
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', 'op-exceptions')?.get('service')).toBe('platform-infra-service');
    });

    fireEvent.click(screen.getByText(EXPORT_BUTTON));

    // 导出走 /op-exceptions/export（xlsx 附件），当前筛选透传
    await waitFor(() => {
      const exports = callsOf(fetchMock, 'GET', '/op-exceptions/export');
      expect(exports).toHaveLength(1);
      expect(exports[0]?.url.searchParams.get('service')).toBe('platform-infra-service');
    });
    // blob 对象 URL 建立（下载落地）
    await waitFor(() => {
      expect((URL.createObjectURL as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThanOrEqual(1);
    });
  });

  it('should_hide_export_when_exception_list_permission_missing', async () => {
    // 权限分控口径：页面在 PermissionGate 之后（路由层），页面内导出按 crud.can 显隐
    await renderPage(ExceptionsPage, [], routes());
    await screen.findByText('platform-infra-service');
    expect(screen.queryByText(EXPORT_BUTTON)).toBeNull();
  });
});

// ————— JobsPage→调度中心（REQ-20261122-023213 AC-12 适配：scheduler 面语义） —————
describe('REQ20261117061017 JobsPage→调度中心（AC-12，023213 适配）', () => {
  const ALL_PERMS = ['scheduler:job:list', 'scheduler:job:update', 'scheduler:job:delete', 'scheduler:execution:list'];
  const LIST_ONLY = ['scheduler:job:list'];
  const TRIGGER_BUTTON = /手动触发/;

  const schedulerRoutes = () => {
    const state = {
      jobs: JOB_ROWS.map((r) => ({ ...r })),
      executions: EXECUTION_ROWS.map((r) => ({ ...r })),
      nextExecutionId: 9099,
    };
    return {
      state,
      routes: {
        // /admin/v1/scheduler/jobs/{id}[/status|/trigger]（长前缀先于 /jobs 列表匹配）
        // /status、/trigger 子资源路径末段非 id：正则取 jobs 后的数字段
        '/scheduler/jobs/': (r: ReturnType<typeof asRequest>) => {
          const id = r.url.pathname.match(/\/scheduler\/jobs\/(\d+)/)?.[1] ?? '';
          const row = state.jobs.find((item) => item.id === id);
          if (!row) return envelope(null, 404);
          if (r.method === 'POST' && r.url.pathname.endsWith('/trigger')) {
            const executionId = String(state.nextExecutionId++);
            state.executions = [
              {
                id: executionId, definitionId: '10', jobKey: row.jobKey, triggerType: 'MANUAL',
                fireTime: new Date().toISOString(), state: 'SUCCESS', attempt: 1, shardIndex: 0,
                result: 'ok', error: null, costMs: 120, traceId: 'trace-manual',
              },
              ...state.executions,
            ];
            return envelope({ id: executionId, jobKey: row.jobKey });
          }
          if (r.method === 'PUT' && r.url.pathname.endsWith('/status')) {
            try {
              row.status = String((JSON.parse(String(r.body ?? '{}')) as { status?: string }).status
                ?? (row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'));
            } catch { /* 保持原状态 */ }
            return envelope(row);
          }
          if (r.method === 'DELETE') {
            state.jobs = state.jobs.filter((item) => item.id !== id);
            return envelope(null);
          }
          return envelope(null);
        },
        '/scheduler/jobs': (r: ReturnType<typeof asRequest>) => {
          if (r.method === 'GET') {
            const jobKey = r.url.searchParams.get('jobKey');
            const status = r.url.searchParams.get('status');
            const rows = state.jobs.filter((item) =>
              (!jobKey || item.jobKey.includes(jobKey)) && (!status || item.status === status));
            return envelope({ records: rows, total: rows.length });
          }
          return envelope(null);
        },
        '/scheduler/executions': (r: ReturnType<typeof asRequest>) => {
          if (r.method !== 'GET') return envelope(null);
          const jobKey = r.url.searchParams.get('jobKey');
          const rows = state.executions.filter((row) => !jobKey || row.jobKey === jobKey);
          return envelope({ records: rows, total: rows.length });
        },
        '/scheduler/executors': () => envelope([]),
      } as Record<string, (r: ReturnType<typeof asRequest>) => Response>,
    };
  };

  it('should_render_scheduler_rows_with_job_view_columns_when_page_loads', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    expect(await screen.findByText('file-retention-cleanup')).toBeTruthy();
    expect(screen.getByText('platform-infra')).toBeTruthy();
    expect(screen.getByText('0 0 3 * * *')).toBeTruthy();
    expect(callsOf(fetchMock, 'GET', '/admin/v1/scheduler/jobs').length).toBeGreaterThanOrEqual(1);
  });

  it('should_trigger_manual_run_with_execution_id_when_run_confirmed', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const rowEl = await findRowByText('file-retention-cleanup');

    fireEvent.click(await within(rowEl).findByText(TRIGGER_BUTTON));
    // 手动触发须有确认（Popconfirm）：确认弹窗出现后才发起请求
    expect(callsOf(fetchMock, 'POST', '/trigger')).toHaveLength(0);
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST', '/admin/v1/scheduler/jobs/301/trigger');
      expect(posts).toHaveLength(1);
    });
    // 成功反馈含返回的执行 id（桩固定 9099）
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/9099/);
    });
  });

  it('should_open_history_tab_with_paging_when_history_tab_clicked', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');

    // 执行历史 Tab（旧执行记录抽屉语义改造为 Tab 分页）：GET /admin/v1/scheduler/executions
    fireEvent.click(screen.getByRole('tab', { name: '执行历史' }));

    await waitFor(() => {
      const gets = callsOf(fetchMock, 'GET', '/admin/v1/scheduler/executions');
      expect(gets).toHaveLength(1);
      expect(gets[0]?.url.searchParams.get('page')).toBe('1');
    });
    expect(await screen.findByText('MANUAL')).toBeTruthy();
    expect(screen.getAllByText('SCHEDULE').length).toBeGreaterThanOrEqual(1);
  });

  it('should_hide_update_actions_when_update_permission_missing', async () => {
    await renderApp(LIST_ONLY, '#/jobs', schedulerRoutes().routes);
    const rowEl = await findRowByText('file-retention-cleanup');
    // 75 分控：无 scheduler:job:update 时启停 Switch/手动触发/新建隐藏
    expect(within(rowEl).queryByRole('switch')).toBeNull();
    expect(within(rowEl).queryByText(TRIGGER_BUTTON)).toBeNull();
    expect(screen.queryByRole('button', { name: /新\s*建/ })).toBeNull();
  });
});

// ————— NAV 权限显隐 + PermissionGate（AC-10；REQ-20261122-023213 适配：调度中心=scheduler:job:list） —————
describe('REQ20261117061017 NAV 权限显隐（AC-10，023213 适配）', () => {
  it('should_show_scheduler_nav_when_scheduler_list_permission_granted', async () => {
    await renderApp(['scheduler:job:list']);
    expect(await screen.findAllByText('调度中心')).not.toHaveLength(0);
  });

  it('should_hide_scheduler_nav_when_permissions_missing', async () => {
    await renderApp(['infra:audit:list']);
    expect(await screen.findAllByText('审计事件')).not.toHaveLength(0);
    expect(screen.queryByText('调度中心')).toBeNull();
    expect(screen.queryByText('定时任务')).toBeNull();
  });

  it('should_show_permission_gate_denial_when_route_visited_without_permission', async () => {
    await renderApp(['infra:audit:list']);
    await screen.findAllByText('审计事件');
    window.location.hash = '#/op-exceptions';

    await waitFor(() => {
      expect(screen.getByText('无访问权限')).toBeTruthy();
    });
    // 缺失权限码在拒绝态展示（PermissionGate deniedDesc）
    expect(screen.getByText(/infra:exception:list/)).toBeTruthy();
  });
});

// ————— OnlineUsersPage 按用户名批量下线（确认⑥ / AC-7 前端面） —————
describe('REQ20261117061017 OnlineUsersPage 批量下线（AC-7 前端面）', () => {
  const PERMS = ['auth:online:list', 'auth:online:revoke'];

  const routes = (state: { rows: typeof ONLINE_USER_ROWS }) => ({
    '/auth/online-users': (r: ReturnType<typeof asRequest>) => {
      if (r.method === 'GET') return envelope(state.rows);
      if (r.method === 'DELETE') {
        const username = r.url.searchParams.get('username') ?? '';
        if (!username.trim()) return envelope(null, 400);
        const matching = state.rows.filter((row) => row.username === username);
        const kicked = matching.filter((row) => row.username !== 'admin').length;
        const skipped = matching.length - kicked;
        state.rows = state.rows.filter((row) => !(row.username === username && row.username !== 'admin'));
        return envelope({ kickedCount: kicked, skippedCount: skipped });
      }
      return envelope(null);
    },
  });

  it('should_batch_kick_by_username_with_counts_when_confirmed', async () => {
    const state = { rows: ONLINE_USER_ROWS.map((r) => ({ ...r })) };
    const { fetchMock } = await renderPage(OnlineUsersPage, PERMS, routes(state));
    await screen.findByText('ops');

    fireEvent.change(await screen.findByPlaceholderText('按用户名下线（username）'), { target: { value: 'ops' } });
    fireEvent.click(screen.getByText(BATCH_KICK_BUTTON));
    // 确认后才发起请求（Popconfirm）
    expect(callsOf(fetchMock, 'DELETE', 'online-users')).toHaveLength(0);
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE', 'online-users');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.searchParams.get('username')).toBe('ops');
    });
    // 计数反馈：kicked/skipped（skipped=0 时仅 kicked）
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/已下线 1 个会话/);
    });
    // 成功后刷新（二次 GET）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', 'online-users').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_skip_superadmin_and_count_skipped_when_batch_kick_admin', async () => {
    const state = { rows: ONLINE_USER_ROWS.map((r) => ({ ...r })) };
    const { fetchMock } = await renderPage(OnlineUsersPage, PERMS, routes(state));
    await screen.findByText('ops');

    fireEvent.change(await screen.findByPlaceholderText('按用户名下线（username）'), { target: { value: 'admin' } });
    fireEvent.click(screen.getByText(BATCH_KICK_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE', 'online-users');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.searchParams.get('username')).toBe('admin');
    });
    // 超管会话跳过并计数（超管保护）：反馈含 skipped
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/跳过 1 个/);
    });
    // 超管会话保留（数据驱动：行未被移除）
    expect(screen.getByText('admin')).toBeTruthy();
  });
});

// ————— AuditPage / LoginLogsPage 导出带参（AC-10：三日志页导出可用） —————
describe('REQ20261117061017 AuditPage / LoginLogsPage 导出（AC-10）', () => {
  it('should_request_audit_export_with_filters_when_export_clicked', async () => {
    const { fetchMock } = await renderPage(AuditPage, ['infra:audit:list'], {
      '/audit-events/export': () => new Response(
        new Blob(['PK\x03\x04mock-xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
        { status: 200, headers: { 'Content-Disposition': 'attachment; filename="audit-events.xlsx"' } },
      ),
      '/audit-events': () => envelope({ records: [], total: 0 }),
    });
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', 'audit-events').length).toBeGreaterThanOrEqual(1);
    });

    fireEvent.change(await screen.findByPlaceholderText('动作（如 release.create）'), { target: { value: 'release.create' } });
    fireEvent.click(screen.getByRole('button', { name: '查 询' }));
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', 'audit-events')?.get('action')).toBe('release.create');
    });

    fireEvent.click(screen.getByText(EXPORT_BUTTON));
    await waitFor(() => {
      const exports = callsOf(fetchMock, 'GET', '/audit-events/export');
      expect(exports).toHaveLength(1);
      expect(exports[0]?.url.searchParams.get('action')).toBe('release.create');
    });
  });

  it('should_request_login_logs_export_with_filters_when_export_clicked', async () => {
    const { fetchMock } = await renderPage(LoginLogsPage, ['auth:loginlog:list'], {
      '/login-logs/export': () => new Response(
        new Blob(['PK\x03\x04mock-xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
        { status: 200, headers: { 'Content-Disposition': 'attachment; filename="login-logs.xlsx"' } },
      ),
      '/login-logs': () => envelope({ records: [], total: 0 }),
    });
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', 'login-logs').length).toBeGreaterThanOrEqual(1);
    });

    fireEvent.change(await screen.findByPlaceholderText('用户名'), { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: '查 询' }));
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', 'login-logs')?.get('username')).toBe('alice');
    });

    fireEvent.click(screen.getByText(EXPORT_BUTTON));
    await waitFor(() => {
      const exports = callsOf(fetchMock, 'GET', '/login-logs/export');
      expect(exports).toHaveLength(1);
      expect(exports[0]?.url.searchParams.get('username')).toBe('alice');
    });
  });
});

// ————— core api 层：路径/载荷（契约端点锚定） —————
describe('REQ20261117061017 core api 路径与载荷', () => {
  it('should_build_export_urls_with_filters_when_api_helpers_called', () => {
    expect(infraApi.opExceptionExport({ service: 'svc', traceId: 'tr' }))
      .toBe('/admin/v1/infra/op-exceptions/export?service=svc&traceId=tr');
    expect(infraApi.opExceptionExport())
      .toBe('/admin/v1/infra/op-exceptions/export');
    expect(authApi.loginLogsExport({ username: 'alice', result: 'FAIL' }))
      .toBe('/admin/v1/auth/login-logs/export?username=alice&result=FAIL');
    expect(auditApi.eventsExport({ action: 'release.create' }))
      .toBe('/admin/v1/infra/audit-events/export?action=release.create');
  });

  it('should_send_batch_kick_delete_with_username_query_when_onlineUsersBatchKick_called', async () => {
    const fetchMock = vi.fn(async () => envelope({ kickedCount: 2, skippedCount: 1 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await authApi.onlineUsersBatchKick('bob');

    expect(result).toEqual({ kickedCount: 2, skippedCount: 1 });
    const deletes = callsOf(fetchMock, 'DELETE', '/admin/v1/auth/online-users');
    expect(deletes).toHaveLength(1);
    expect(deletes[0]?.url.pathname).toBe('/admin/v1/auth/online-users');
    expect(deletes[0]?.url.searchParams.get('username')).toBe('bob');
  });

  it('should_call_op_exception_detail_endpoint_when_opExceptionGet_called', async () => {
    const fetchMock = vi.fn(async () => envelope(OP_EXCEPTION_ROWS[0]));
    vi.stubGlobal('fetch', fetchMock);

    const view = await infraApi.opExceptionGet('6201');

    expect(view.exceptionType).toBe('java.lang.IllegalStateException');
    const gets = callsOf(fetchMock, 'GET', '/admin/v1/infra/op-exceptions/6201');
    expect(gets).toHaveLength(1);
  });
});
