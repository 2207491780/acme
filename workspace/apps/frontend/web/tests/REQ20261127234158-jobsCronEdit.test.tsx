/**
 * REQ-20261127-234158（wire 契约·DTO 字段对齐 P1-10）调度任务 CRON/FIXED_RATE 编辑链路行为级用例
 * （AC-4 新建载荷长名 / AC-5 视图字符串回填换算）。
 *
 * 【行为级，非对照】与 frontend-dev 已交付的 web/tests/REQ20261127234158-schedulerMenuWireContract.test.ts
 * （非 MSW 纯数据 wire 对照，AC-9 FE fixture 侧锚）互补：本文件从「页面提交链路」视角断言
 * JobsPage 新建/编辑请求体使用长名 cronExpr/fixedRateMs（不再出现短名 cron/fixedRate），
 * FIXED_RATE 视图 wire 字符串 fixedRateMs 回填换算为 number 提交。
 *
 * 钉死 wire golden（事实源=backend REQ20261127234158*WireTest.java 的 JobView/JobSaveRequest 用例）：
 *   - 载荷（JobSaveRequest）：键 cronExpr（CRON 必填）/fixedRateMs（FIXED_RATE 必填，Long 毫秒），
 *     FE 载荷发 number（后端反序列化 number/string 双口径）；短名 cron/fixedRate 不映射；
 *   - 视图（JobView）：fixedRateMs/timeoutMs/id/version wire 字符串（LongToStringModule）、
 *     shardTotal（Integer）数值、cronExpr 字符串或 null。
 *
 * 装配照既有范本 web/tests/REQ20261122023213-schedulerAdminPage.test.tsx（fetch stubGlobal 弱匹配路由
 * + useMeStore 权限播种 + <AntApp> + HashRouter 整棵 App 渲染；fixture 已按本 REQ wire 对齐）；
 * 交互断言照 pitfall 7/8（下拉操作发到 .ant-select-selector、pickOption 限定在下拉浮层容器内）、
 * pitfall 10/13/14（文案宽容正则、负例断言用键集/raw 正文 regex 宽匹配）。
 *
 * 【AMB 假设（低风险，失败即 TL 裁决；pitfall 11 不自由发挥）】
 *   - AMB-1：新建/编辑 Drawer 表单字段定位沿用范本 AMB-4 口径（label 宽容正则 + .ant-form-item 容器）；
 *     FIXED_RATE 字段 label 未有先例，用 /fixedRate|固定|频率|间隔/i 宽容匹配。
 *   - AMB-2：Drawer 触发类型控件按 label /trigger|触发/（兜底 /类型/）定位；为 Select 时经下拉浮层
 *     结构性点选（选项文案 /FIXED_RATE|固定/），为 Radio 时直接点选对应项。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 120000 });

// ————— 行为常量（范本口径：两字按钮 autoInsertSpace 兼容 + 文案宽容正则） —————
const CONFIRM_BUTTON = /确\s*(定|认)|^OK$/i;
const CREATE_BUTTON = /新\s*建/;
const EDIT_BUTTON = /编\s*辑/;

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

const parseBody = (body: unknown): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '{}')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

// ————— fixtures（契约钉死形状；REQ-20261127-234158 wire 对齐：id/version/fixedRateMs/timeoutMs
//      视图 wire 字符串、shardTotal 数值、cronExpr 字符串或 null） —————
const JOB_ROWS = [
  {
    id: '601', version: '0', jobKey: 'file-retention-cleanup', appName: 'platform-infra',
    triggerType: 'CRON', cronExpr: '0 0 3 * * *', fixedRateMs: null, nextFireTime: '2026-11-29T03:00:00Z',
    status: 'ENABLED', timeoutMs: '60000', shardTotal: 1,
  },
  {
    id: '605', version: '2', jobKey: 'custom-report-sync', appName: 'business-report',
    triggerType: 'FIXED_RATE', cronExpr: null, fixedRateMs: '300000', nextFireTime: '2026-11-29T10:05:00Z',
    status: 'ENABLED', timeoutMs: '30000', shardTotal: 2,
  },
];

type Routes = Record<string, (r: Req) => Response>;

// ————— 调度任务路由桩（可变状态数据驱动；/scheduler/jobs/ 前缀须先于 /scheduler/jobs 匹配） —————
const schedulerRoutes = () => {
  const state = {
    jobs: JOB_ROWS.map((r) => ({ ...r })),
    nextId: 700,
  };
  const routes: Routes = {
    '/scheduler/jobs/': (r) => {
      const id = r.url.pathname.match(/\/scheduler\/jobs\/(\d+)/)?.[1] ?? '';
      const row = state.jobs.find((item) => item.id === id);
      if (!row) return envelope(null, 404);
      if (r.method === 'PUT') {
        const body = parseBody(r.body);
        if (body.jobKey != null && body.jobKey !== row.jobKey) {
          return errorEnvelope('platform-scheduler-01-0003', 'job_key 创建后不可修改');
        }
        Object.assign(row, body);
        return envelope(row);
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
          return errorEnvelope('platform-scheduler-01-0001', '任务 Key 已存在');
        }
        const created = {
          id: String(state.nextId++), version: '0', status: 'DISABLED',
          // wire 对齐：载荷/视图键长名 cronExpr/fixedRateMs（body 覆盖默认值）
          appName: '', triggerType: 'CRON', cronExpr: null, fixedRateMs: null,
          nextFireTime: null, timeoutMs: '60000', shardTotal: 1, ...body,
        };
        state.jobs.push(created as unknown as (typeof state.jobs)[number]);
        return envelope(created);
      }
      return envelope(null);
    },
  };
  return { state, routes };
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
    accessToken: 'token-234158',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 整棵 App 渲染（路由/NAV/PermissionGate 口径，范本 renderApp）。 */
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

/** Drawer 内 label 关联字段定位（范本 AMB-4：AntD Form label 可能关联非 labellable 元素，按 form-item 容器取 input）。 */
const fieldInput = (drawer: HTMLElement, label: RegExp): HTMLInputElement => {
  const labelEl = Array.from(drawer.querySelectorAll('label')).find((el) => label.test(el.textContent ?? ''));
  const input = labelEl?.closest('.ant-form-item')?.querySelector('input') as HTMLInputElement | null;
  if (!input) {
    throw new Error(`Drawer 表单字段 ${label} 未找到；labels=[${
      Array.from(drawer.querySelectorAll('label')).map((el) => el.textContent ?? '').join(' | ')}]`);
  }
  return input;
};

const drawerEl = () => document.querySelector('.ant-drawer-content') as HTMLElement;

/** 下拉选择（pitfall 7/8：操作发到 .ant-select-selector，选项限定在下拉浮层容器内；
 *  选项文案宽容正则结构性遍历 .ant-select-item-option，失败附实际选项清单）。 */
const pickOptionByPattern = async (combobox: HTMLElement, pattern: RegExp): Promise<void> => {
  const selector = (combobox.closest('.ant-select') ?? combobox.parentElement) as HTMLElement;
  fireEvent.mouseDown(selector.querySelector('.ant-select-selector') ?? selector);
  const dropdown = await waitFor(() => {
    const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    expect(el).toBeTruthy();
    return el as HTMLElement;
  });
  const options = Array.from(dropdown.querySelectorAll('.ant-select-item-option'));
  const target = options.find((opt) =>
    pattern.test(opt.querySelector('.ant-select-item-option-content')?.textContent?.trim() ?? ''));
  if (!target) {
    throw new Error(`下拉选项 ${pattern} 未找到；options=[${
      options.map((opt) => opt.querySelector('.ant-select-item-option-content')?.textContent?.trim()).join(' | ')}]`);
  }
  fireEvent.click(target);
};

/** Drawer 触发类型控件切换（AMB-2：Select 走下拉浮层点选，Radio 直接点选对应项）。 */
const switchTriggerType = async (drawer: HTMLElement, optionPattern: RegExp): Promise<void> => {
  const items = Array.from(drawer.querySelectorAll('.ant-form-item'));
  const item = items.find((el) => /trigger|触发/i.test(el.querySelector('label')?.textContent ?? ''))
    ?? items.find((el) => /类型/i.test(el.querySelector('label')?.textContent ?? ''));
  if (!item) {
    throw new Error(`触发类型控件未找到；labels=[${
      Array.from(drawer.querySelectorAll('label')).map((el) => el.textContent ?? '').join(' | ')}]`);
  }
  const select = item.querySelector('.ant-select') as HTMLElement | null;
  if (select) {
    await pickOptionByPattern(select, optionPattern);
    return;
  }
  const radio = Array.from(item.querySelectorAll('.ant-radio-button-wrapper, .ant-radio-wrapper'))
    .find((el) => optionPattern.test(el.textContent ?? ''));
  if (!radio) {
    throw new Error(`触发类型既非 Select 也无匹配 Radio；item=${
      (item.textContent ?? '').slice(0, 200)}`);
  }
  fireEvent.click(radio);
};

const openCreateDrawer = async (): Promise<void> => {
  await screen.findByText('file-retention-cleanup');
  // 限定当前 Tab pane：页头/全局区也可能存在同名「新建」按钮（范本口径）
  const pane = document.querySelector('.ant-tabs-tabpane-active') as HTMLElement;
  fireEvent.click(within(pane).getAllByRole('button', { name: CREATE_BUTTON })[0]!);
  await waitFor(() => {
    expect(drawerEl()).toBeTruthy();
  });
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

// ————— AC-4：新建 CRON 任务 → 请求体含 cronExpr、不含短名 cron/fixedRate；创建后回读非空 —————
describe('REQ20261127234158 新建 CRON 任务载荷（AC-4）', () => {
  it('should_post_long_name_cron_expr_payload_without_short_names_when_cron_job_created', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await openCreateDrawer();
    const drawer = drawerEl();

    // 范本 AMB-4：Drawer 表单字段定位（宽容 label 正则 + form-item 容器）
    fireEvent.change(fieldInput(drawer, /jobKey|任务 Key|任务标识/i), { target: { value: 'req234158-cron-job' } });
    fireEvent.change(fieldInput(drawer, /appName|应用名|应用/i), { target: { value: 'platform-infra' } });
    fireEvent.change(fieldInput(drawer, /cron/i), { target: { value: '0 15 5 * * *' } });
    fireEvent.click(within(drawer).getByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'POST', '/scheduler/jobs')).toHaveLength(1);
    });
    const posts = callsOf(fetchMock, 'POST', '/scheduler/jobs');
    const body = parseBody(posts[0]?.body);
    // 长名在、短名不在（任一侧漂移即红；pitfall 13：raw 正文 regex 宽匹配兜底嵌套序列化）
    expect(body.cronExpr, '载荷键应为 cronExpr（长名）').toBe('0 15 5 * * *');
    expect(body.triggerType).toBe('CRON');
    expect('cron' in body, '载荷不得含短名 cron 键').toBe(false);
    expect('fixedRate' in body, '载荷不得含短名 fixedRate 键').toBe(false);
    expect(String(posts[0]?.body ?? '')).not.toMatch(/"cron"\s*:/);
    expect(String(posts[0]?.body ?? '')).not.toMatch(/"fixedRate"\s*:/);

    // 创建后回读：mock 任务新增行呈现，CRON 字段值非空（AC-4 回读面）
    const row = await findRowByText('req234158-cron-job');
    expect(within(row).getByText('0 15 5 * * *')).toBeTruthy();
  });
});

// ————— AC-4：新建 FIXED_RATE 任务 → 触发类型切 FIXED_RATE，载荷含 fixedRateMs（number）、无短名 —————
describe('REQ20261127234158 新建 FIXED_RATE 任务载荷（AC-4）', () => {
  it('should_post_fixed_rate_ms_number_payload_without_short_names_when_fixed_rate_job_created', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    await openCreateDrawer();
    const drawer = drawerEl();

    fireEvent.change(fieldInput(drawer, /jobKey|任务 Key|任务标识/i), { target: { value: 'req234158-rate-job' } });
    fireEvent.change(fieldInput(drawer, /appName|应用名|应用/i), { target: { value: 'business-report' } });
    // 触发类型切 FIXED_RATE（AMB-2：Select 下拉浮层点选 / Radio 点选）
    await switchTriggerType(drawer, /FIXED_RATE|固定/);
    // FIXED_RATE 字段（AMB-1：label 宽容正则）
    fireEvent.change(fieldInput(drawer, /fixedRate|固定|频率|间隔/i), { target: { value: '300000' } });
    fireEvent.click(within(drawer).getByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      expect(callsOf(fetchMock, 'POST', '/scheduler/jobs')).toHaveLength(1);
    });
    const posts = callsOf(fetchMock, 'POST', '/scheduler/jobs');
    const body = parseBody(posts[0]?.body);
    // 载荷 fixedRateMs 发 number（后端反序列化 number/string 双口径；FE 载荷口径钉 number）
    expect(body.triggerType).toBe('FIXED_RATE');
    expect(body.fixedRateMs, 'FIXED_RATE 载荷键应为 fixedRateMs（长名，number）').toBe(300000);
    expect(body.fixedRateMs).toBeTypeOf('number');
    expect('fixedRate' in body, '载荷不得含短名 fixedRate 键').toBe(false);
    expect('cron' in body, '载荷不得含短名 cron 键').toBe(false);
    expect(String(posts[0]?.body ?? '')).not.toMatch(/"fixedRate"\s*:/);
    expect(String(posts[0]?.body ?? '')).not.toMatch(/"cron"\s*:/);
    expect(body.cronExpr ?? null, 'FIXED_RATE 任务 cronExpr 为 null').toBeNull();

    // 创建后回读：mock 任务新增行呈现（AC-4 回读面）
    expect(await findRowByText('req234158-rate-job')).toBeTruthy();
  });
});

// ————— AC-5：FIXED_RATE 视图 wire 字符串 fixedRateMs 编辑回填换算 → number 提交 —————
describe('REQ20261127234158 编辑回填换算（AC-5）', () => {
  it('should_backfill_numeric_fixed_rate_from_wire_string_and_submit_number_when_fixed_rate_row_edited', async () => {
    const { fetchMock } = await renderApp(ALL_PERMS, '#/jobs', schedulerRoutes().routes);
    const row = await findRowByText('custom-report-sync');

    // 打开编辑 Drawer：视图 wire fixedRateMs="300000"（字符串口径）
    fireEvent.click(await within(row).findByText(EDIT_BUTTON));
    // 回填换算：表单呈现数值 300000（非 NaN/空）
    expect(await screen.findByDisplayValue('300000')).toBeTruthy();
    const drawer = drawerEl();

    fireEvent.click(within(drawer).getByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/scheduler/jobs/605')).toHaveLength(1);
    });
    const puts = callsOf(fetchMock, 'PUT', '/scheduler/jobs/605');
    const body = parseBody(puts[0]?.body);
    // 提交载荷：fixedRateMs 为 number 300000（Number(record.fixedRateMs) 换算；字符串直传即红）
    expect(body.fixedRateMs, '编辑提交载荷 fixedRateMs 应换算为 number').toBe(300000);
    expect(body.fixedRateMs).toBeTypeOf('number');
    // 长名口径：无短名键
    expect('fixedRate' in body).toBe(false);
    expect('cron' in body).toBe(false);
    expect(String(puts[0]?.body ?? '')).not.toMatch(/"fixedRate"\s*:/);
    expect(String(puts[0]?.body ?? '')).not.toMatch(/"cron"\s*:/);
    // FIXED_RATE 行编辑提交不携带 cron 表达式（cronExpr null 或缺省）
    expect(body.cronExpr ?? null).toBeNull();
  });
});
