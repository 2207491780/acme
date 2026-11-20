/**
 * REQ-20261120-214653 AC-10~15（web 面）：权限点管理页筛选（关键字＋状态/HTTP 方法下拉＋重置）
 * 与状态列。装配照既有范本：web/tests/REQ20261114071607-usersPage.test.tsx（fetch stubGlobal 弱匹配
 * 路由 + useMeStore 播种 + <AntApp> + testTimeout ≥15000）＋ REQ20261114120104-usersPageDeptFilter
 * （进阶筛选展开 + 下拉点选口径）＋ REQ20261117125500-filterPanel（FilterPanel/CrudTable 表面口径：
 * region 筛选条件 / label 关键词 / 展开进阶筛选 / 查询 / 重置）。
 *
 * 【TL 裁决口径（2026-11-20 通报）】
 *   ① 状态/HTTP 方法下拉与重置按钮置于「展开进阶筛选」折叠区内（参照 UsersPage 形态）：
 *      交互路径 = 先展开进阶筛选再操作（对齐 usersPageDeptFilter）。
 *   ② AC-5（httpMethod 为 null 不命中）归后端集成测试验证；web 夹具不放 httpMethod=null 样例，
 *      AC-14 web 覆盖 = keyword/status/httpMethod 过滤＋DISABLED 样例命中。
 *   ③ 实现侧 mock 已补 1 条 DISABLED 权限点（id '3'）——实现自备数据，本套件夹具独立自建。
 *   ④ 不触碰 SystemApiSurfaceTest（backend 编译兼容补参条款）。
 *
 * 【表面假定（契约未钉死文案，未命中即红灯交 TL 裁决，A-14 口径）】
 *   F-1 关键字输入框：label「关键词」（CrudTable 内建，filterPanel 先例）→ label /关\s*键/ →
 *       placeholder 含「搜索」（crudPages A-32）三通道发现。
 *   F-2 状态下拉：label /状\s*态/（或 placeholder/selection-placeholder 同文案）。
 *   F-3 HTTP 方法下拉：label /HTTP\s*方?法|方\s*法/（或 placeholder 同文案）。
 *   F-4 选项显示名：状态=「启用/停用」或 wire 值「ENABLED/DISABLED」（正则双兼容点选）；
 *       HTTP 方法=动词原样（GET/POST/...，wire 值即显示名的强先例：LoginLogs 端=「B」、
 *       Releases 环境=「生产」→ 显示名实现自选，超出正则色域则本用例红、交 TL）。
 *   F-5 状态列：列头「状态」；标签文案=「ENABLED/DISABLED」或「启用/停用」（AC-15「标签展示」）。
 *   F-6 空态文案：antd zh_CN 缺省「暂无数据」（或 en「No data」）。
 *   F-7 筛选提交形态（查询按钮 / 选择自动触发）未钉死：存在 /查\s*询/ 按钮才点击；
 *       参数断言以 latest GET + waitFor 收敛，两种形态均兼容。
 *   F-8 AC-14 的 msw handler（src/mocks/handlers/system.ts）为禁读实现且无导入面：
 *       本套件以「契约 §API 同语义 fetch stub（含 DISABLED 样例）＋页面消费」覆盖其可观察面；
 *       真实 handler 过滤行为由前端工人自验/浏览器验收。
 *
 * 【契约同语义 stub（AC-14 可观察面）】keyword=permissionCode/permissionName 包含（不含 httpPath，
 * AC-2 范围不扩展）；status=ENABLED|DISABLED 精确；httpMethod=动词精确；多条件 AND；
 * 空值参数不参与过滤。夹具含 DISABLED 样例 2 条（B/E）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore } from '@acme/core';
import { PermissionsPage } from '../src/pages/PermissionsPage';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量 —————
const PERMS_PATH_PART = '/permissions';
const PERM = { list: ['system:permission:list'] };
const EXPAND_TOGGLE = '展开进阶筛选';

// ————— 夹具（AdminPermissionView wire 口径；TL 裁决②：不放 httpMethod=null 样例） —————
type PermissionRow = {
  id: string; permissionCode: string; permissionName: string; permissionType: string;
  httpMethod: string | null; httpPath: string | null; status: 'ENABLED' | 'DISABLED';
  createdAt: string; version: string;
};

const PERM_ROWS: PermissionRow[] = [
  // A：GET＋ENABLED，code 含「perm」→ keyword 命中
  { id: '9201', permissionCode: 'demo:perm:list', permissionName: '样例甲z7k', permissionType: 'API', httpMethod: 'GET', httpPath: '/demo/perm/list', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
  // B：POST＋DISABLED（DISABLED 样例），code 含「perm」
  { id: '9202', permissionCode: 'demo:perm:create', permissionName: '样例乙z7k', permissionType: 'API', httpMethod: 'POST', httpPath: '/demo/perm/create', status: 'DISABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
  // C：PUT＋ENABLED——httpPath 含「perm」但 code/name 不含 → keyword「perm」不命中（范围不扩展陷阱）
  { id: '9203', permissionCode: 'demo:other:upload', permissionName: '样例丙z7k', permissionType: 'API', httpMethod: 'PUT', httpPath: '/demo/perm/upload', status: 'ENABLED', createdAt: '2026-01-03T00:00:00Z', version: '0' },
  // E：DELETE＋DISABLED（DISABLED 样例），code 含「perm」
  { id: '9205', permissionCode: 'demo:perm:erase', permissionName: '样例戊z7k', permissionType: 'API', httpMethod: 'DELETE', httpPath: '/demo/perm/erase', status: 'DISABLED', createdAt: '2026-01-05T00:00:00Z', version: '0' },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— fetch 边界 mock（契约 §API 同语义过滤，AC-14 可观察面） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

/** 契约 §API 命中语义：keyword=code/name 包含（不含 httpPath）；status/httpMethod 精确；AND；空值不过滤。 */
const applyContractFilters = (rows: PermissionRow[], query: URLSearchParams) => {
  const keyword = (query.get('keyword') ?? '').toLowerCase();
  const status = query.get('status') ?? '';
  const httpMethod = query.get('httpMethod') ?? '';
  return rows.filter((row) => {
    if (keyword
      && !(row.permissionCode.toLowerCase().includes(keyword)
        || row.permissionName.toLowerCase().includes(keyword))) {
      return false;
    }
    if (status && row.status !== status) return false;
    if (httpMethod && row.httpMethod !== httpMethod) return false;
    return true;
  });
};

const setupPermissionsApi = () => {
  const state = { rows: PERM_ROWS.map((row) => ({ ...row })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes(PERMS_PATH_PART)) return envelope(null);
    if (r.method === 'GET') {
      // 详情端点（/{id}）双兼容回空：本套件不触达（crudPages 深层路径口径）
      if (/\/permissions\/\d+$/.test(r.url.pathname)) return envelope(null);
      const matched = applyContractFilters(state.rows, r.url.searchParams);
      return envelope({ records: matched, total: matched.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(PERMS_PATH_PART) && r.method === method);

const lastQuery = (fetchMock: ReturnType<typeof vi.fn>) =>
  callsOf(fetchMock, 'GET').at(-1)?.url.searchParams ?? null;

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

const renderPage = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupPermissionsApi();
  render(<AntApp><PermissionsPage /></AntApp>);
  return api;
};

// ————— 筛选控件发现（TL 裁决①：下拉/重置在「展开进阶筛选」折叠区内——先展开再操作） —————

const ensureAdvancedOpen = async () => {
  const toggle = screen.queryByRole('button', { name: EXPAND_TOGGLE });
  if (!toggle) return; // 无折叠切换（字段直显）：直接操作
  if (toggle.getAttribute('aria-expanded') !== 'true') {
    fireEvent.click(toggle);
  }
  await waitFor(() => {
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });
};

/** F-1：关键字输入框（基础区，不展开）：label「关键词」→ /关\s*键/ → placeholder 含「搜索」 */
const findKeywordInput = async (): Promise<HTMLInputElement> => {
  try {
    return (await screen.findByLabelText('关键词', {}, { timeout: 2000 })) as HTMLInputElement;
  } catch { /* 下一通道 */ }
  try {
    return (await screen.findByLabelText(/关\s*键/, {}, { timeout: 1000 })) as HTMLInputElement;
  } catch { /* 下一通道 */ }
  const byPlaceholder = Array.from(document.querySelectorAll<HTMLInputElement>('input[placeholder]'))
    .find((el) => /搜\s*索/.test(el.placeholder ?? ''));
  if (byPlaceholder) return byPlaceholder;
  throw new Error('未发现关键字搜索框（label=关键词、/关键/、placeholder 含「搜索」均未命中）——交 TL 裁决');
};

/** F-2/F-3：下拉筛选控件（先展开进阶筛选，TL 裁决①）；label → input placeholder → selection-placeholder 三通道 */
const findFilterControl = async (labelRe: RegExp, hintRe: RegExp, hintName: string): Promise<HTMLElement> => {
  await ensureAdvancedOpen();
  try {
    return await screen.findByLabelText(labelRe, {}, { timeout: 2000 });
  } catch { /* 下一通道 */ }
  const byInputPlaceholder = Array.from(document.querySelectorAll<HTMLInputElement>('input[placeholder]'))
    .find((el) => hintRe.test(el.placeholder ?? ''));
  if (byInputPlaceholder) return byInputPlaceholder;
  const bySelectionPlaceholder = Array.from(
    document.querySelectorAll<HTMLElement>('.ant-select-selection-placeholder'),
  ).find((el) => hintRe.test(el.textContent ?? ''));
  if (bySelectionPlaceholder) return bySelectionPlaceholder;
  throw new Error(`未发现「${hintName}」筛选控件（label ${labelRe}、placeholder/selection-placeholder ${hintRe} 均未命中）——交 TL 裁决`);
};

/** F-4：在当前开放下拉容器内按显示名正则点选选项（禁 role=option 探针，usersPageDeptFilter 口径）；未命中抛错并附现有选项 */
const pickOption = async (optionRe: RegExp, selectLabel: string) => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  });
  const dropdown = document.querySelector<HTMLElement>(
    '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
  ) as HTMLElement;
  const options = Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'));
  const target = options.find((option) => optionRe.test(option.textContent ?? ''));
  if (!target) {
    throw new Error(`「${selectLabel}」下拉未命中选项 ${optionRe}；现有选项：${
      options.map((option) => option.textContent ?? '').join(' | ')}`);
  }
  fireEvent.click(target);
};

/** F-7：存在查询按钮才点击（选择自动触发形态下跳过，两种形态均兼容） */
const submitFilters = () => {
  const queryButton = screen.queryByRole('button', { name: /查\s*询/ });
  if (queryButton) fireEvent.click(queryButton);
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

// ————— 生命周期 —————
beforeEach(async () => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
  stubBrowserGaps();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
});

// ————— AC-10：筛选区装配（关键字搜索框＋两下拉＋重置入口；初始空值不进查询串） —————
describe('REQ20261120214653 权限点管理筛选（AC-10~15）', () => {
  it('should_render_search_and_dropdown_filters_with_reset_when_page_loads', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    expect(await screen.findByText('demo:perm:list')).toBeTruthy();

    // 筛选面板 region（filterPanel CrudTable 口径）＋关键字搜索框（基础区）
    expect(screen.getByRole('region', { name: '筛选条件' })).toBeTruthy();
    expect(await findKeywordInput()).toBeTruthy();
    // 状态/HTTP 方法两下拉＋重置入口（进阶折叠区，TL 裁决①）
    await findFilterControl(/状\s*态/, /状\s*态/, '状态');
    await findFilterControl(/HTTP\s*方?法|方\s*法/, /HTTP|方\s*法/, 'HTTP 方法');
    await ensureAdvancedOpen();
    expect(screen.getByRole('button', { name: /重\s*置/ })).toBeTruthy();

    // AC-11 前置口径「空值不进查询串」：初始请求不携带任何筛选参数
    const query = lastQuery(fetchMock);
    expect(query?.has('keyword')).toBe(false);
    expect(query?.has('status')).toBe(false);
    expect(query?.has('httpMethod')).toBe(false);
    expect(query?.get('page')).toBe('1');
  });

  // ————— AC-11（关键字）＋AC-2 展示面：关键字过滤 + 范围不扩展 + 回页首 —————
  it('should_send_keyword_and_show_matched_rows_when_search_submitted', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');
    const input = await findKeywordInput();
    fireEvent.change(input, { target: { value: 'perm' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    await waitFor(() => expect(lastQuery(fetchMock)?.get('keyword')).toBe('perm'));
    expect(lastQuery(fetchMock)?.get('page')).toBe('1');
    // 命中：A/B/E（code 含 perm；stub 契约语义）
    expect(await screen.findByText('demo:perm:create')).toBeTruthy();
    expect(screen.getByText('demo:perm:erase')).toBeTruthy();
    // 范围不扩展：C 的 httpPath 含 perm 但 code/name 不含 → 不命中；D 类非关键字行不命中
    await waitFor(() => expect(screen.queryByText('demo:other:upload')).toBeNull());
  });

  // ————— AC-11（状态）：状态下拉 → status=ENABLED 随请求携带，仅启用行展示 —————
  it('should_send_status_param_and_filter_rows_when_enabled_picked', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');
    const control = await findFilterControl(/状\s*态/, /状\s*态/, '状态');
    fireEvent.mouseDown(control);
    await pickOption(/启\s*用|ENABLED/i, '状态');
    await submitFilters();

    await waitFor(() => expect(lastQuery(fetchMock)?.get('status')).toBe('ENABLED'));
    expect(await screen.findByText('demo:other:upload')).toBeTruthy();
    expect(screen.getByText('demo:perm:list')).toBeTruthy();
    // DISABLED 样例（B/E）被过滤
    await waitFor(() => expect(screen.queryByText('demo:perm:create')).toBeNull());
    await waitFor(() => expect(screen.queryByText('demo:perm:erase')).toBeNull());
  });

  // ————— AC-11（HTTP 方法）：方法下拉 → httpMethod=GET 随请求携带，仅 GET 行展示 —————
  // （AC-5「null 不命中」归后端集成测试验证，TL 裁决②）
  it('should_send_http_method_param_and_filter_rows_when_get_picked', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');
    const control = await findFilterControl(/HTTP\s*方?法|方\s*法/, /HTTP|方\s*法/, 'HTTP 方法');
    fireEvent.mouseDown(control);
    await pickOption(/^GET$/i, 'HTTP 方法');
    await submitFilters();

    await waitFor(() => expect(lastQuery(fetchMock)?.get('httpMethod')).toBe('GET'));
    expect(await screen.findByText('demo:perm:list')).toBeTruthy();
    // 非 GET 行（B=POST、C=PUT、E=DELETE）被过滤
    await waitFor(() => expect(screen.queryByText('demo:perm:create')).toBeNull());
    await waitFor(() => expect(screen.queryByText('demo:other:upload')).toBeNull());
    await waitFor(() => expect(screen.queryByText('demo:perm:erase')).toBeNull());
  });

  // ————— AC-6 展示面＋AC-11：keyword＋status＋httpMethod 三条件 AND —————
  it('should_intersect_keyword_status_and_method_when_combined_filters_applied', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');

    const input = await findKeywordInput();
    fireEvent.change(input, { target: { value: 'perm' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    const statusControl = await findFilterControl(/状\s*态/, /状\s*态/, '状态');
    fireEvent.mouseDown(statusControl);
    await pickOption(/停\s*用|DISABLED/i, '状态');

    const methodControl = await findFilterControl(/HTTP\s*方?法|方\s*法/, /HTTP|方\s*法/, 'HTTP 方法');
    fireEvent.mouseDown(methodControl);
    await pickOption(/^POST$/i, 'HTTP 方法');
    await submitFilters();

    await waitFor(() => {
      const query = lastQuery(fetchMock);
      expect(query?.get('keyword')).toBe('perm');
      expect(query?.get('status')).toBe('DISABLED');
      expect(query?.get('httpMethod')).toBe('POST');
    });
    // AND 交集：仅 B（perm×DISABLED×POST）命中；E 为 DISABLED×DELETE 不命中
    expect(await screen.findByText('demo:perm:create')).toBeTruthy();
    await waitFor(() => expect(screen.queryByText('demo:perm:list')).toBeNull());
    await waitFor(() => expect(screen.queryByText('demo:perm:erase')).toBeNull());
    await waitFor(() => expect(screen.queryByText('demo:other:upload')).toBeNull());
  });

  // ————— AC-12：重置 → 清空全部条件（不进查询串）＋恢复全量列表（回到第 1 页） —————
  it('should_clear_filters_and_restore_full_list_when_reset_clicked', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');

    // 先施加关键字＋状态两条件
    const input = await findKeywordInput();
    fireEvent.change(input, { target: { value: 'perm' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(lastQuery(fetchMock)?.get('keyword')).toBe('perm'));
    const statusControl = await findFilterControl(/状\s*态/, /状\s*态/, '状态');
    fireEvent.mouseDown(statusControl);
    await pickOption(/停\s*用|DISABLED/i, '状态');
    await submitFilters();
    await waitFor(() => expect(lastQuery(fetchMock)?.get('status')).toBe('DISABLED'));

    // 重置（进阶折叠区，TL 裁决①）
    await ensureAdvancedOpen();
    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    await waitFor(() => {
      const query = lastQuery(fetchMock);
      expect(query?.has('keyword')).toBe(false);
      expect(query?.has('status')).toBe(false);
      expect(query?.has('httpMethod')).toBe(false);
      expect(query?.get('page')).toBe('1');
    });
    // 恢复全量列表（4 行夹具全可见）
    expect(await screen.findByText('demo:perm:list')).toBeTruthy();
    expect(screen.getByText('demo:perm:create')).toBeTruthy();
    expect(screen.getByText('demo:other:upload')).toBeTruthy();
    expect(screen.getByText('demo:perm:erase')).toBeTruthy();
  });

  // ————— AC-13：筛选结果为空 → 表格空态提示 —————
  it('should_show_empty_state_when_filters_match_nothing', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('demo:perm:list');
    const input = await findKeywordInput();
    fireEvent.change(input, { target: { value: 'zzz-no-match' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(lastQuery(fetchMock)?.get('keyword')).toBe('zzz-no-match'));

    // 空态文案（F-6：antd zh_CN 缺省「暂无数据」；兼容 en「No data」。
    // round1 打回修正：Empty 的 SVG <title> 与 ant-empty-description 双命中 → findAllByText ≥1 收敛）
    expect((await screen.findAllByText(/暂\s*无\s*数\s*据|no\s*data/i)).length).toBeGreaterThanOrEqual(1);
  });

  // ————— AC-15：表格状态列（ENABLED/DISABLED 标签展示） —————
  it('should_render_status_column_tags_when_rows_loaded', async () => {
    await renderPage(PERM.list);
    // 列头「状态」（F-5）
    const headers = screen.getAllByRole('columnheader').map((header) => header.textContent ?? '');
    expect(headers.some((text) => /状\s*态/.test(text))).toBe(true);

    // 行内标签：启用行 / 停用行（F-5：wire 值或 zh 文案双兼容）
    const enabledRow = await findRowByText('demo:perm:list');
    expect(within(enabledRow).getByText(/ENABLED|启\s*用/)).toBeTruthy();
    const disabledRow = await findRowByText('demo:perm:create');
    expect(within(disabledRow).getByText(/DISABLED|停\s*用/)).toBeTruthy();
  });
});
