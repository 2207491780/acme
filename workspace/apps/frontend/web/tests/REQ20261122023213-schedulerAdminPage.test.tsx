/** @vitest-environment jsdom */
/**
 * REQ-20261122-023213（JOB-3）调度中心管理页（/jobs 三 Tab）页面级测试，AC-1~AC-9。
 *
 * 覆盖：
 *   AC-1 三 Tab 渲染 + 标题「调度中心」+ 路由/NAV 权限点 scheduler:job:list（73）+ infra:job:list 用户无入口；
 *   AC-2 任务 Tab 分页列表（jobKey/status 过滤）+ JobView 精简列集 + DISABLED 种子行启用动作；
 *   AC-3 启停 Switch → PUT /{id}/status 专用端点 + 种子行确认弹窗（接管说明 + cron 提示）+ 状态即时迁移；
 *   AC-4 手动触发 Popconfirm → POST /{id}/trigger → 返回执行 id + 成功反馈；
 *   AC-5 新建/编辑 Drawer + KEY_DUPLICATE 映射 + job_key 编辑态只读；
 *   AC-6 删除 Popconfirm(76) → DELETE 软删 + 列表刷新；
 *   AC-7 历史 Tab 过滤器（jobKey/state/时间窗）参数装配 + ExecutionView 字段渲染；
 *   AC-8 监控 Tab executors 列表 + ONLINE/OFFLINE 徽标；
 *   AC-9 权限 75/76 分控（无 75 隐藏启停/触发/新建/编辑；无 76 隐藏删除）。
 *
 * 装配照既有范本 web/tests/REQ20261117061017-loggingJobs.test.tsx：
 *   fetch stubGlobal 弱匹配路由 + useMeStore 权限播种 + <AntApp> + HashRouter 整棵 App 渲染
 *   （契约「JobsPage 改造 或 新组件+路由复用」两种实现口径下均成立，不直接 import 页面组件）；
 *   testTimeout ≥15000ms；交互断言照 pitfall 7/8：下拉操作发到 .ant-select-selector，
 *   pickOption 限定在下拉浮层容器内；断言口径数据驱动 + 宽容正则（pitfall 10/13/14）。
 *
 * 【fixture 形状 = 契约钉死事实】
 *   JobAdminView 列集精简列：jobKey/appName/triggerType/cronExpr 或 fixedRateMs/nextFireTime/status/timeoutMs/shardTotal
 *   （id/version wire 字符串化；REQ-20261127-234158 对齐——fixedRateMs/timeoutMs 视图 wire 字符串）；
 *   ExecutionView 12 字段；种子 4 行 DISABLED、appName=platform-infra、cronExpr 见契约背景。
 *
 * 【AMB 假设（TL 裁决锚点）】
 *   - AMB-1：Tab 名取确认⑤字面量（任务定义/执行历史/执行器监控）；
 *   - AMB-2：筛选控件定位用宽容正则（jobKey 输入 placeholder / jobKey|任务标识 /；状态筛选为首个下拉），
 *     文案若偏离由 TL 裁决（pitfall 14 宽出口径）；
 *   - AMB-3：历史 Tab 时间窗经 .ant-picker 两个输入 fireEvent.change 装配，fireTimeFrom/To 参数断言非空不钉格式；
 *   - AMB-4：Drawer 表单字段定位用 label 宽容正则（jobKey/appName/cron）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore } from '@acme/core';

// testTimeout 160s（TL round-2 预算放宽，判定链闭环后披露制：theme4-idle-rerun.log 失败用例闲置实测
// 最大 63.95s；新预算=max(实测×2.5, 现预算×2)=159885→160s，重交互用例 ≥60s 口径；断言/行为零改动）
vi.setConfig({ testTimeout: 160000 });

// ————— 行为常量（范本口径：两字按钮 autoInsertSpace 兼容 + 文案宽容正则） —————
const CONFIRM_BUTTON = /确\s*(定|认)|^OK$/i;
const QUERY_BUTTON = /查\s*询/;
const CREATE_BUTTON = /新\s*建/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /^删\s*除$/;
const TRIGGER_BUTTON = /手动触发/;
const TAB_TASKS = '任务定义';
const TAB_HISTORY = '执行历史';
const TAB_MONITORS = '执行器监控';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const errorEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

// ————— fetch 边界 mock（范本 helpers） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];
type Req = ReturnType<typeof asRequest>;

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

const parseBody = (body: unknown): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '{}')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

// ————— fixtures（契约钉死形状；id/version wire string） —————
// REQ-20261127-234158 wire 对齐：视图键长名 cronExpr/fixedRateMs（字符串口径，LongToStringModule）
const JOB_ROWS = [
  {
    id: '301', version: '0', jobKey: 'file-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cronExpr: '0 0 3 * * *', fixedRateMs: null, nextFireTime: '2026-11-23T03:00:00Z',
    status: 'DISABLED', timeoutMs: '60000', shardTotal: 1,
  },
  {
    id: '302', version: '0', jobKey: 'login-log-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cronExpr: '0 30 3 * * *', fixedRateMs: null, nextFireTime: '2026-11-23T03:30:00Z',
    status: 'DISABLED', timeoutMs: '60000', shardTotal: 1,
  },
  {
    id: '303', version: '0', jobKey: 'exception-log-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cronExpr: '0 40 3 * * *', fixedRateMs: null, nextFireTime: '2026-11-23T03:40:00Z',
    status: 'DISABLED', timeoutMs: '60000', shardTotal: 1,
  },
  {
    id: '304', version: '0', jobKey: 'audit-log-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cronExpr: '0 40 4 * * *', fixedRateMs: null, nextFireTime: '2026-11-23T04:40:00Z',
    status: 'DISABLED', timeoutMs: '60000', shardTotal: 1,
  },
  {
    id: '305', version: '2', jobKey: 'custom-report-sync', appName: 'business-report',
    triggerType: 'FIXED_RATE', cronExpr: null, fixedRateMs: '300000', nextFireTime: '2026-11-22T10:05:00Z',
    status: 'ENABLED', timeoutMs: '30000', shardTotal: 1,
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

const EXECUTOR_ROWS = [
  {
    id: 'exec-1', appName: 'platform-infra', execUrl: 'http://10.0.0.11:9411',
    state: 'ONLINE', lastHeartbeat: '2026-11-22T10:00:00Z',
  },
  {
    id: 'exec-2', appName: 'business-report', execUrl: 'http://10.0.0.12:9411',
    state: 'OFFLINE', lastHeartbeat: '2026-11-21T10:00:00Z',
  },
];

type Routes = Record<string, (r: Req) => Response>;

// ————— 调度中心路由桩（可变状态数据驱动；/scheduler/jobs/ 前缀须先于 /scheduler/jobs 匹配） —————
const schedulerRoutes = () => {
  const state = {
    jobs: JOB_ROWS.map((r) => ({ ...r })),
    executions: EXECUTION_ROWS.map((r) => ({ ...r })),
    executors: EXECUTOR_ROWS.map((r) => ({ ...r })),
    nextId: 400,
    nextExecutionId: 9099,
  };
  const jobsDetailRoutes: Routes = {
    // .../scheduler/jobs/{id}[/status|/trigger]
    '/scheduler/jobs/': (r) => {
      // /status、/trigger 子资源路径末段非 id：正则取 jobs 后的数字段
      const id = r.url.pathname.match(/\/scheduler\/jobs\/(\d+)/)?.[1] ?? '';
      const row = state.jobs.find((item) => item.id === id);
      if (!row) return envelope(null, 404);

      if (r.method === 'PUT' && r.url.pathname.endsWith('/status')) {
        const body = parseBody(r.body);
        row.status = String(body.status ?? (row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'));
        return envelope(row);
      }
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
      if (r.method === 'PUT') {
        const body = parseBody(r.body);
        if (body.jobKey != null && body.jobKey !== row.jobKey) {
          return errorEnvelope('KEY_IMMUTABLE', 'job_key 创建后不可修改');
        }
        Object.assign(row, body);
        return envelope(row);
      }
      if (r.method === 'DELETE') {
        state.jobs = state.jobs.filter((item) => item.id !== id);
        return envelope(null);
      }
      return envelope(null);
    },
    '/scheduler/jobs': (r) => {
      if (r.method === 'GET') {
        const jobKey = r.url.searchParams.get('jobKey');
        const status = r.url.searchParams.get('status');
        const rows = state.jobs.filter((item) =>
          (!jobKey || item.jobKey.includes(jobKey)) && (!status || item.status === status));
        return envelope({ records: rows, total: rows.length });
      }
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        if (state.jobs.some((item) => item.jobKey === body.jobKey)) {
          return errorEnvelope('KEY_DUPLICATE', '任务 Key 已存在');
        }
        const created = {
          id: String(state.nextId++), version: '0', status: 'DISABLED',
          // REQ-20261127-234158 wire 对齐：载荷/视图键长名 cronExpr/fixedRateMs（body 覆盖默认值）
          appName: '', triggerType: 'CRON', cronExpr: null, fixedRateMs: null,
          nextFireTime: null, timeoutMs: '60000', shardTotal: 1, ...body,
        };
        state.jobs.push(created as unknown as (typeof state.jobs)[number]);
        return envelope(created);
      }
      return envelope(null);
    },
    '/scheduler/executions': (r) => {
      if (r.method !== 'GET') return envelope(null);
      const jobKey = r.url.searchParams.get('jobKey');
      const filterState = r.url.searchParams.get('state');
      const fireTimeFrom = r.url.searchParams.get('fireTimeFrom');
      const fireTimeTo = r.url.searchParams.get('fireTimeTo');
      const rows = state.executions.filter((item) =>
        (!jobKey || item.jobKey.includes(jobKey))
        && (!filterState || item.state === filterState)
        && (!fireTimeFrom || item.fireTime >= fireTimeFrom)
        && (!fireTimeTo || item.fireTime <= fireTimeTo));
      return envelope({ records: rows, total: rows.length });
    },
    '/scheduler/executors': () => envelope(state.executors),
  };
  return { state, routes: jobsDetailRoutes };
};

// ————— 装配（范本口径） —————
const stubBrowserGaps = () => {
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

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-023213',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 整棵 App 渲染（路由/NAV/PermissionGate 口径，loggingJobs 范本 renderApp 扩展 routes）。 */
const renderApp = async (
  permissions: string[],
  hash = '#/',
  routes?: Routes,
): Promise<{ fetchMock: ReturnType<typeof vi.fn> }> => {
  seedSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.url.pathname.includes('/system/me')) {
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
      if (r.url.pathname.includes(part)) return handler(r);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const findRowByText = async (anchor: string): Promise<HTMLElement> => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** 下拉选择（pitfall 7/8：操作发到 .ant-select-selector，选项限定在下拉浮层容器内）。 */
const pickOption = async (combobox: HTMLElement, label: string): Promise<void> => {
  const selector = (combobox.closest('.ant-select') ?? combobox.parentElement) as HTMLElement;
  fireEvent.mouseDown(selector.querySelector('.ant-select-selector') ?? selector);
  const dropdown = await waitFor(() => {
    const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    expect(el).toBeTruthy();
    return el as HTMLElement;
  });
  fireEvent.click(await within(dropdown).findByText(label));
};

const openTab = async (name: string): Promise<void> => {
  fireEvent.click(screen.getByRole('tab', { name }));
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
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

const ALL_PERMS = [
  'scheduler:job:list', 'scheduler:job:create', 'scheduler:job:update',
  'scheduler:job:delete', 'scheduler:execution:list',
];

// ————— AC-1：三 Tab 渲染 + 标题 + 路由/NAV 权限点 73 + infra:job:list 无入口 —————
describe('REQ20261122023213 三 Tab 与路由权限（AC-1）', () => {
  it('should_render_three_tabs_and_title_when_jobs_route_visited', async () => {
    await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);

    expect(screen.getByRole('tab', { name: TAB_TASKS })).toBeTruthy();
    expect(screen.getByRole('tab', { name: TAB_HISTORY })).toBeTruthy();
    expect(screen.getByRole('tab', { name: TAB_MONITORS })).toBeTruthy();
    // 标题「调度中心」（NAV 与页面标题同文案，存在即达标；多命中用 findAll）
    expect(await screen.findAllByText('调度中心')).not.toHaveLength(0);
  });

  it('should_show_nav_entry_when_scheduler_list_permission_granted', async () => {
    await renderApp(['scheduler:job:list']);
    expect(await screen.findAllByText('调度中心')).not.toHaveLength(0);
  });

  it('should_hide_nav_entry_and_gate_route_when_only_infra_job_list_granted', async () => {
    await renderApp(['infra:job:list']);
    expect(await screen.findAllByText('概览')).not.toHaveLength(0);
    expect(screen.queryByText('调度中心'), '静态 NAV 不再有调度中心入口（infra:job:list 用户）').toBeNull();

    window.location.hash = '#/jobs';
    await waitFor(() => {
      expect(screen.getByText('无访问权限'), '路由 PermissionGate 拒绝').toBeTruthy();
    });
    expect(screen.getByText(/scheduler:job:list/), '缺失权限码在拒绝态展示').toBeTruthy();
  });
});

// ————— AC-2：任务 Tab 分页列表 + 列集 + DISABLED 种子行启用动作 —————
describe('REQ20261122023213 任务 Tab 列表（AC-2）', () => {
  it('should_render_task_rows_with_job_view_columns_when_tasks_tab_loaded', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);

    const row = await findRowByText('file-retention-cleanup');
    expect(within(row).getByText('platform-infra')).toBeTruthy();
    expect(within(row).getByText('0 0 3 * * *')).toBeTruthy();
    expect(within(row).getByText('DISABLED')).toBeTruthy();
    // 精简列集锚点：timeoutMs/shardTotal 列值在行内呈现（60000/1）
    expect(within(row).getAllByText('60000').length).toBeGreaterThanOrEqual(1);
    // 分页参数：page=1 起始
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', '/scheduler/jobs')?.get('page')).toBe('1');
    });
  });

  it('should_render_enable_action_on_disabled_seed_rows_when_loaded', async () => {
    await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);

    const seedRow = await findRowByText('file-retention-cleanup');
    const seedSwitch = within(seedRow).getByRole('switch') as HTMLButtonElement;
    expect(seedSwitch.getAttribute('aria-checked'), 'DISABLED 种子行呈现启用动作（Switch 未勾选）').toBe('false');

    const enabledRow = await findRowByText('custom-report-sync');
    const enabledSwitch = within(enabledRow).getByRole('switch') as HTMLButtonElement;
    expect(enabledSwitch.getAttribute('aria-checked'), 'ENABLED 行 Switch 已勾选').toBe('true');
  });

  it('should_assemble_job_key_and_status_filters_when_query_submitted', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');

    // AMB-2：jobKey 筛选输入（宽容正则）
    fireEvent.change(screen.getByPlaceholderText(/jobKey|任务标识|任务 Key/i), { target: { value: 'file-retention' } });
    fireEvent.click(screen.getByRole('button', { name: QUERY_BUTTON }));
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', '/scheduler/jobs')?.get('jobKey')).toBe('file-retention');
    });
    expect(lastQuery(fetchMock, 'GET', '/scheduler/jobs')?.get('page'), '筛选提交重置到第 1 页').toBe('1');

    // AMB-2：status 筛选下拉限定在当前 Tab pane（全局首个 combobox 可能是页头组件）
    const pane = document.querySelector('.ant-tabs-tabpane-active') as HTMLElement;
    await pickOption(within(pane).getAllByRole('combobox')[0]!, 'DISABLED');
    fireEvent.click(within(pane).getByRole('button', { name: QUERY_BUTTON }));
    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', '/scheduler/jobs')?.get('status')).toBe('DISABLED');
    });
  });
});

// ————— AC-3：启停 Switch → PUT /status + 种子行确认弹窗文案 + 状态即时迁移 —————
describe('REQ20261122023213 启停（AC-3）', () => {
  it('should_show_seed_takeover_confirm_copy_with_cron_hint_when_seed_row_switch_clicked', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    fireEvent.click(within(row).getByRole('switch'));

    // 确认弹窗文案（确认⑥字面量）+ 种子 cron + 运维核对提示（不做自动校对）
    expect(await screen.findByText(/启用即接管调度/)).toBeTruthy();
    expect((document.body.textContent ?? '')).toMatch(/retention-cron/);
    expect((document.body.textContent ?? '')).toMatch(/0 0 3 \* \* \*/);
    // 未确认不发起请求
    expect(callsOf(fetchMock, 'PUT', '/status')).toHaveLength(0);

    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', '/scheduler/jobs/301/status');
      expect(puts, '启停走 status 专用端点').toHaveLength(1);
    });
    // 状态即时迁移（数据驱动：桩翻状态，页面回读）
    await waitFor(async () => {
      const after = await findRowByText('file-retention-cleanup');
      expect(within(after).getByText('ENABLED')).toBeTruthy();
    });
  });

  it('should_put_status_endpoint_when_switch_confirmed_on_generic_row', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('custom-report-sync');

    fireEvent.click(within(row).getByRole('switch'));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/scheduler/jobs/305/status')).toHaveLength(1);
    });
    await waitFor(async () => {
      const after = await findRowByText('custom-report-sync');
      expect(within(after).getByText('DISABLED')).toBeTruthy();
    });
  });
});

// ————— AC-4：手动触发 Popconfirm → POST /{id}/trigger → 执行 id 反馈 —————
describe('REQ20261122023213 手动触发（AC-4）', () => {
  it('should_post_trigger_and_show_execution_id_feedback_when_trigger_confirmed', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    fireEvent.click(await within(row).findByText(TRIGGER_BUTTON));
    // Popconfirm 二次确认：未确认不发起请求
    expect(callsOf(fetchMock, 'POST', '/trigger')).toHaveLength(0);
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST', '/scheduler/jobs/301/trigger');
      expect(posts).toHaveLength(1);
    });
    // 成功反馈含返回的执行 id（桩固定 9099）
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/9099/);
    });
  });
});

// ————— AC-5：新建/编辑 Drawer + KEY_DUPLICATE 映射 + job_key 编辑态只读 —————
describe('REQ20261122023213 新建/编辑 Drawer（AC-5）', () => {
  const drawerEl = () => document.querySelector('.ant-drawer-content') as HTMLElement;
  /** AntD Form label 关联的 input 可能被包裹为非 labellable 元素：按 form-item 容器定位（pitfall 9）。 */
  const fieldInput = (drawer: HTMLElement, label: RegExp): HTMLInputElement => {
    const labelEl = Array.from(drawer.querySelectorAll('label')).find((el) => label.test(el.textContent ?? ''));
    const input = labelEl?.closest('.ant-form-item')?.querySelector('input') as HTMLInputElement | null;
    expect(input, `Drawer 表单字段 ${label}`).toBeTruthy();
    return input!;
  };

  it('should_map_key_duplicate_error_when_create_submitted_with_conflicting_job_key', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');

    // 限定当前 Tab pane：页头/全局区也可能存在同名「新建」按钮
    const pane = document.querySelector('.ant-tabs-tabpane-active') as HTMLElement;
    fireEvent.click(within(pane).getAllByRole('button', { name: CREATE_BUTTON })[0]!);
    const drawer = await waitFor(() => {
      const el = drawerEl();
      expect(el).toBeTruthy();
      return el;
    });

    // AMB-4：Drawer 表单字段定位（宽容 label 正则 + form-item 容器）
    fireEvent.change(fieldInput(drawer, /jobKey|任务 Key|任务标识/i), { target: { value: 'file-retention-cleanup' } });
    fireEvent.change(fieldInput(drawer, /appName|应用名|应用/i), { target: { value: 'platform-infra' } });
    fireEvent.change(fieldInput(drawer, /cron/i), { target: { value: '0 0 3 * * *' } });
    fireEvent.click(within(drawer).getByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'POST', '/scheduler/jobs')).toHaveLength(1);
    });
    // KEY_DUPLICATE 映射展示（宽容：映射文案或错误码任一呈现）
    await waitFor(() => {
      expect((document.body.textContent ?? '')).toMatch(/已存在|重复|KEY_DUPLICATE/);
    });
  });

  it('should_open_edit_drawer_with_job_key_readonly_and_put_when_submitted', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    fireEvent.click(await within(row).findByText(EDIT_BUTTON));
    const input = await screen.findByDisplayValue('file-retention-cleanup') as HTMLInputElement;
    expect(input.disabled, 'job_key 编辑态只读（KEY_IMMUTABLE 前端面）').toBe(true);

    fireEvent.click(within(drawerEl()).getByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/scheduler/jobs/301')).toHaveLength(1);
    });
  });
});

// ————— AC-6：删除 Popconfirm(76) → DELETE 软删 + 列表刷新 —————
describe('REQ20261122023213 删除（AC-6）', () => {
  it('should_delete_after_popconfirm_and_refresh_list_when_delete_clicked', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('custom-report-sync');

    fireEvent.click(await within(row).findByText(DELETE_BUTTON));
    // Popconfirm 二次确认：未确认不发起请求
    expect(callsOf(fetchMock, 'DELETE', '/scheduler/jobs')).toHaveLength(0);
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'DELETE', '/scheduler/jobs/305')).toHaveLength(1);
    });
    // 列表刷新：行移除（数据驱动）
    await waitFor(() => {
      expect(screen.queryByText('custom-report-sync'), '删除后行不再呈现').toBeNull();
    });
    expect(callsOf(fetchMock, 'GET', '/scheduler/jobs').length).toBeGreaterThanOrEqual(2);
  });
});

// ————— AC-7：历史 Tab 分页 + 过滤器参数装配 + ExecutionView 字段渲染 —————
describe('REQ20261122023213 执行历史 Tab（AC-7）', () => {
  it('should_request_executions_with_paging_and_render_execution_view_when_history_tab_opened', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');

    await openTab(TAB_HISTORY);

    await waitFor(() => {
      const gets = callsOf(fetchMock, 'GET', '/scheduler/executions');
      expect(gets.length).toBeGreaterThanOrEqual(1);
      expect(gets[0]!.url.searchParams.get('page')).toBe('1');
    });
    // ExecutionView 字段渲染（数据驱动锚点）
    expect(await screen.findByText('trace-exec-1')).toBeTruthy();
    expect(screen.getByText('MANUAL')).toBeTruthy();
    expect(screen.getByText('SCHEDULE')).toBeTruthy();
    expect(screen.getByText('SUCCESS')).toBeTruthy();
    expect(screen.getByText('FAILED')).toBeTruthy();
    expect(screen.getByText('trace-exec-2')).toBeTruthy();
  });

  it('should_assemble_job_key_and_state_filters_when_history_query_submitted', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');
    await openTab(TAB_HISTORY);
    await waitFor(() => expect(callsOf(fetchMock, 'GET', '/scheduler/executions').length).toBeGreaterThanOrEqual(1));

    // AMB-2：历史过滤器 jobKey 输入（历史 Tab 作用域内的输入）
    const pane = document.querySelector('.ant-tabs-tabpane-active') as HTMLElement;
    fireEvent.change(within(pane).getByPlaceholderText(/jobKey|任务标识|任务 Key/i), { target: { value: 'file-retention-cleanup' } });
    // AMB-2：state 筛选下拉（历史 Tab 内首个 combobox）
    await pickOption(within(pane).getAllByRole('combobox')[0]!, 'SUCCESS');
    fireEvent.click(within(pane).getByRole('button', { name: QUERY_BUTTON }));

    await waitFor(() => {
      expect(lastQuery(fetchMock, 'GET', '/scheduler/executions')?.get('jobKey')).toBe('file-retention-cleanup');
    });
    expect(lastQuery(fetchMock, 'GET', '/scheduler/executions')?.get('state')).toBe('SUCCESS');
  });

  it('should_assemble_fire_time_window_params_when_history_range_picked', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');
    await openTab(TAB_HISTORY);
    await waitFor(() => expect(callsOf(fetchMock, 'GET', '/scheduler/executions').length).toBeGreaterThanOrEqual(1));

    // AMB-3：RangePicker 两输入 change 装配时间窗（参数名契约钉死 fireTimeFrom/To，格式不钉）
    const pane = document.querySelector('.ant-tabs-tabpane-active') as HTMLElement;
    const pickerInputs = Array.from(pane.querySelectorAll('.ant-picker input')) as HTMLInputElement[];
    expect(pickerInputs.length, '历史过滤器含时间窗 RangePicker').toBeGreaterThanOrEqual(2);
    fireEvent.change(pickerInputs[0]!, { target: { value: '2026-11-01 00:00:00' } });
    fireEvent.change(pickerInputs[1]!, { target: { value: '2026-11-22 23:59:59' } });
    fireEvent.click(within(pane).getByRole('button', { name: QUERY_BUTTON }));

    await waitFor(() => {
      const from = lastQuery(fetchMock, 'GET', '/scheduler/executions')?.get('fireTimeFrom');
      const to = lastQuery(fetchMock, 'GET', '/scheduler/executions')?.get('fireTimeTo');
      expect(from, 'fireTimeFrom 参数装配（格式不钉）').toBeTruthy();
      expect(to, 'fireTimeTo 参数装配（格式不钉）').toBeTruthy();
    });
  });
});

// ————— AC-8：监控 Tab executors 列表 + ONLINE/OFFLINE 徽标 —————
describe('REQ20261122023213 执行器监控 Tab（AC-8）', () => {
  it('should_render_executors_with_online_badges_when_monitor_tab_opened', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await screen.findByText('file-retention-cleanup');

    await openTab(TAB_MONITORS);

    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/scheduler/executors')).toHaveLength(1);
    });
    expect(await screen.findByText('ONLINE')).toBeTruthy();
    expect(screen.getByText('OFFLINE')).toBeTruthy();
    expect(screen.getByText('platform-infra')).toBeTruthy();
    expect(screen.getByText('business-report')).toBeTruthy();
  });
});

// ————— AC-9：75/76 分控（无 75 隐藏启停/触发/新建/编辑；无 76 隐藏删除） —————
describe('REQ20261122023213 权限分控（AC-9）', () => {
  it('should_hide_update_actions_when_update_permission_missing', async () => {
    await renderApp(['scheduler:job:list'], '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    expect(screen.queryByRole('button', { name: CREATE_BUTTON }), '无 75：新建隐藏').toBeNull();
    expect(within(row).queryByRole('switch'), '无 75：启停 Switch 隐藏').toBeNull();
    expect(within(row).queryByText(TRIGGER_BUTTON), '无 75：手动触发隐藏').toBeNull();
    expect(within(row).queryByText(EDIT_BUTTON), '无 75：编辑隐藏').toBeNull();
  });

  it('should_hide_delete_when_delete_permission_missing', async () => {
    await renderApp(['scheduler:job:list', 'scheduler:job:update'], '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    expect(within(row).getByRole('switch'), '持 75：启停可见').toBeTruthy();
    expect(within(row).queryByText(DELETE_BUTTON), '无 76：删除隐藏').toBeNull();
  });

  it('should_show_all_actions_when_full_permissions_granted', async () => {
    await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('file-retention-cleanup');

    expect(screen.getAllByRole('button', { name: CREATE_BUTTON }).length).toBeGreaterThan(0);
    expect(within(row).getByRole('switch')).toBeTruthy();
    expect(within(row).getByText(TRIGGER_BUTTON)).toBeTruthy();
    expect(within(row).getByText(EDIT_BUTTON)).toBeTruthy();
    expect(within(row).getByText(DELETE_BUTTON)).toBeTruthy();
  });
});
