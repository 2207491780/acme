/**
 * REQ-20261114-120104 AC-8（前端面·角色数据范围）：RolesPage 数据范围设置弹窗——
 * 弹窗打开、scope 五档 Select（显示名点选）、CUSTOM 时部门多选出现、提交 PUT data-scope、
 * `system:role:scope` 门禁（fail-closed）。
 *
 * 装配照既有范本 web/tests/REQ20261114120102-noticesPage.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式播种 + <AntApp> 包裹 + testTimeout ≥15000ms + 关闭态断言数据驱动；
 * 角色 fixture/CRUD 口径对齐 REQ20261114120101-crudPages 的 Roles 套件。
 *
 * 【表面假定（REQ-20261114-120104 系列 A-1..，汇总见交付说明；待 TL 裁决）】
 *   A-1 页面 web/src/pages/RolesPage.tsx 命名导出 RolesPage（既有页，crudPages A-35 口径）。
 *   A-9 行内动作按钮「数据范围」打开弹窗（备选文案「数据权限」待 TL 裁决）；弹窗内 scope Select
 *       label 恰为「数据范围」（crudPages Permissions「类型」getByLabelText 同型惯例）；
 *       CUSTOM 时出现部门多选 Select，label 恰为「自定义部门」。
 *   A-10 scope 五档显示名假定：ALL=全部数据 / DEPT=本部门 / DEPT_AND_CHILD=本部门及子部门 /
 *       SELF=仅本人 / CUSTOM=自定义（wire 枚举值为契约字面，显示名文案待 TL 裁决）。
 *   A-11 PUT /admin/v1/system/roles/{id}/data-scope 提交体键名未钉死（scope vs dataScope）→
 *       断言 JSON 宽松包含枚举串与 deptId 串（键名无关）。
 *   A-12 RoleView 增列 dataScope（wire 名假定 dataScope；本套件断言键名无关，仅 fixture 承载）。
 *   A-15 下拉点选收敛：经开放下拉容器（.ant-select-dropdown:not(.ant-select-dropdown-hidden)）
 *       within().findByText(显示名) 点选（委派单口径、禁 role=option 探针）；CUSTOM 部门多选
 *       只点根级启用部门（市场部/行政部），规避 TreeSelect 子级展开不确定性。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { RolesPage } from '../src/pages/RolesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const SCOPE_BUTTON = /数\s*据\s*范\s*围/; // A-9
const CONFIRM_BUTTON = /确\s*定|^OK$/i;

const PERM = {
  list: ['system:role:list'],
  scope: ['system:role:scope'],
};

const ROLES_PATH_PART = '/roles';
const DEPTS_PATH_PART = '/depts';

// 角色 fixture（crudPages Roles 套件同形状 + A-12 dataScope 增列）
const ROLE_ROWS: Record<string, unknown>[] = [
  { id: '3101', parentId: '0', roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', children: [], dataScope: 'ALL', version: '0' },
  { id: '3102', parentId: '0', roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', children: [], dataScope: 'ALL', version: '0' },
];

// 部门树 fixture（A-2 同口径；CUSTOM 多选点选目标为根级启用部门：市场部 4003 / 行政部 4005）
type DeptNode = {
  id: string;
  parentId: string;
  deptName: string;
  sortOrder: number;
  status: 'ENABLED' | 'DISABLED';
  children: DeptNode[];
  version: string;
};

const DEPT_TREE: DeptNode[] = [
  {
    id: '4001', parentId: '0', deptName: '总部', sortOrder: 1, status: 'ENABLED', version: '0',
    children: [
      { id: '4002', parentId: '4001', deptName: '研发部', sortOrder: 1, status: 'ENABLED', version: '0', children: [] },
    ],
  },
  { id: '4003', parentId: '0', deptName: '市场部', sortOrder: 2, status: 'ENABLED', version: '0', children: [] },
  { id: '4004', parentId: '0', deptName: '财务部', sortOrder: 3, status: 'DISABLED', version: '0', children: [] },
  { id: '4005', parentId: '0', deptName: '行政部', sortOrder: 4, status: 'ENABLED', version: '0', children: [] },
];

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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(ROLES_PATH_PART) && r.method === method);

const dataScopePuts = (fetchMock: ReturnType<typeof vi.fn>) =>
  callsOf(fetchMock, 'PUT').filter((r) => r.url.pathname.endsWith('/data-scope')); // 委派单口径 6：endsWith 收敛

const setupRolesApi = () => {
  const state = { rows: ROLE_ROWS.map((row) => ({ ...row })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p.includes(DEPTS_PATH_PART)) return envelope(DEPT_TREE); // CUSTOM 部门多选选项源
    if (!p.includes(ROLES_PATH_PART)) return envelope(null);
    if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
    if (r.method === 'PUT') {
      // 数据范围子资源（契约 §API：PUT /roles/{id}/data-scope）先于通配 PUT 识别
      if (p.endsWith('/data-scope')) {
        const segments = p.split('/').filter(Boolean);
        const id = segments[segments.length - 2] ?? '';
        return envelope(state.rows.find((row) => row.id === id) ?? null);
      }
      const body = parseBody(r.body);
      const id = p.split('/').filter(Boolean).pop() ?? '';
      state.rows = state.rows.map((row) => (row.id === id ? { ...row, ...body } : row));
      return envelope(state.rows.find((row) => row.id === id) ?? null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
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

const renderPage = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupRolesApi();
  render(<AntApp><RolesPage /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** 打开「数据范围」弹窗（A-9） */
const openScopeDialog = async () => {
  const rowEl = await findRowByText('运维员'); // 非内置角色行
  fireEvent.click(await within(rowEl).findByText(SCOPE_BUTTON));
  return screen.findByRole('dialog');
};

/** A-15：点选当前开放下拉容器内的显示名选项（禁 role=option 探针） */
const pickOption = async (displayName: string) => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  });
  const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
  fireEvent.click(await within(dropdown).findByText(displayName));
};

const selectionTexts = (scope: HTMLElement) =>
  Array.from(scope.querySelectorAll('.ant-select-selection-item')).map((el) => el.textContent ?? '');

/** 选中回显前置断言（crudPages 失败例 2 口径）：未选中则在此即失败 */
const expectSelection = async (scope: HTMLElement, text: string) => {
  await waitFor(() => {
    expect(selectionTexts(scope).some((t) => t.includes(text))).toBe(true);
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

describe('REQ20261114120104 角色数据范围设置（AC-8·角色数据范围）', () => {
  it('should_open_data_scope_dialog_when_row_scope_action_clicked', async () => {
    await renderPage([...PERM.list, ...PERM.scope]);
    await screen.findByText('超级管理员');

    const dialog = await openScopeDialog();
    // 弹窗承载 scope 五档 Select（A-9/A-10）
    expect(within(dialog).getByLabelText('数据范围')).toBeTruthy();
  });

  it('should_submit_put_data_scope_with_enum_when_dept_and_child_scope_selected', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.scope]);
    await screen.findByText('超级管理员');

    const dialog = await openScopeDialog();
    fireEvent.mouseDown(within(dialog).getByLabelText('数据范围'));
    await pickOption('本部门及子部门'); // DEPT_AND_CHILD（A-10 显示名）
    await expectSelection(dialog, '本部门及子部门');
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(dataScopePuts(fetchMock)).toHaveLength(1));
    const put = dataScopePuts(fetchMock)[0];
    expect(put?.url.pathname).toContain('/3102/');
    // 提交体承载 scope 枚举 wire 值（A-11 键名无关宽松断言）
    expect(JSON.stringify(parseBody(put?.body))).toContain('DEPT_AND_CHILD');
  });

  it('should_submit_custom_dept_ids_when_custom_scope_selected', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.scope]);
    await screen.findByText('超级管理员');

    const dialog = await openScopeDialog();
    fireEvent.mouseDown(within(dialog).getByLabelText('数据范围'));
    await pickOption('自定义'); // CUSTOM（A-10 显示名）
    await expectSelection(dialog, '自定义');

    // CUSTOM 时部门多选出现（A-9）：点选两个根级启用部门
    fireEvent.mouseDown(await within(dialog).findByLabelText('自定义部门'));
    await pickOption('市场部');
    await pickOption('行政部');
    await expectSelection(dialog, '市场部');
    await expectSelection(dialog, '行政部');
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(dataScopePuts(fetchMock)).toHaveLength(1));
    const put = dataScopePuts(fetchMock)[0];
    expect(put?.url.pathname).toContain('/3102/');
    const bodyJson = JSON.stringify(parseBody(put?.body));
    expect(bodyJson).toContain('CUSTOM');
    expect(bodyJson).toContain('4003');
    expect(bodyJson).toContain('4005');
  });

  it('should_hide_data_scope_action_when_scope_permission_missing', async () => {
    await renderPage(PERM.list); // 仅 list 权限，无 system:role:scope（fail-closed）
    await screen.findByText('超级管理员');

    const rowEl = await findRowByText('运维员');
    expect(within(rowEl).queryByText(SCOPE_BUTTON)).toBeNull();
    const builtInRow = await findRowByText('超级管理员');
    expect(within(builtInRow).queryByText(SCOPE_BUTTON)).toBeNull();
  });
});
