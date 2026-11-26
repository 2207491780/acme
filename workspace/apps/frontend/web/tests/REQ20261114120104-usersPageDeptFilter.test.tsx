/**
 * REQ-20261114-120104 AC-8（前端面·用户页部门筛选）：UsersPage 部门筛选（选部门 → 列表请求
 * 携带 deptId）+ 表单含部门/岗位选择（编辑路径提交 PUT 携带 deptId/positionId）。
 *
 * 装配照既有范本 web/tests/REQ20261114120102-noticesPage.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式播种 + <AntApp> 包裹 + testTimeout ≥15000ms + 关闭态断言数据驱动；
 * 编辑回填/确认口径对齐 REQ20261114120101-crudPages（UsersPage 为该套件装配母本）。
 *
 * 【表面假定（REQ-20261114-120104 系列 A-1..，汇总见交付说明；待 TL 裁决）】
 *   A-1 页面 web/src/pages/UsersPage.tsx 命名导出 UsersPage（既有页，crudPages A-35 口径）。
 *   A-13 用户行 fixture = AdminUserView + deptId/positionId（契约「sys_user.dept_id/position_id 列」
 *       wire camelCase）；行锚用 username。既有用户两字段可为 null（存量数据未分配）。
 *   A-14 用户筛选区为 placeholder 含「部门」的 Select（对齐 crudPages A-32 占位符口径）；兼容两种
 *       承载：showSearch 型 input[placeholder] 与非搜索型 rc-select 的 .ant-select-selection-placeholder
 *       div（mouseDown 冒泡开启下拉）；筛选选项为部门显示名——只点根级「总部」规避 TreeSelect
 *       子级展开不确定性；选部门后列表请求携带 deptId（含子部门递归为后端语义，前端只传参）。
 *   A-15 用户表单部门/岗位字段 label 恰为「部门」「岗位」（crudPages Permissions「类型」
 *       getByLabelText 同型惯例）；选项显示名=部门名/岗位名；下拉点选经开放下拉容器
 *       （.ant-select-dropdown:not(.ant-select-dropdown-hidden)）within().findByText(显示名) 收敛
 *       （委派单口径、禁 role=option 探针），防表格行同文案撞车。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { UsersPage } from '../src/pages/UsersPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const EDIT_BUTTON = /编\s*辑/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;

const PERM = {
  list: ['system:user:list'],
  update: ['system:user:update'],
};

const USERS_PATH_PART = '/users';
// REQ-20261125-230807 delta-r3 基线更新（AC-3）：目录源统一走 GET /admin/v1/system/user-form-options，
// 替代本套件原对 /depts、/positions 旧端点的直接 stub（页面已不再调用旧端点，usersDirectory 套件钉死）。
const FORM_OPTIONS_PATH_PART = '/user-form-options';

// 用户 fixture（A-13：AdminUserView + deptId/positionId；zhangsan 未分配部门/岗位 → 编辑路径承载新选值）
const USER_ROWS: Record<string, unknown>[] = [
  { id: '2001', username: 'zhangsan', nickname: '张三', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], deptId: null, positionId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '2002', username: 'lisi', nickname: '李四', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], deptId: '4003', positionId: '5002', createdAt: '2026-01-02T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', version: '0' },
];

// 部门树 fixture（deptsPage A-2 同口径；筛选/表单点选目标只取根级「总部」4001，规避子级展开不确定性）
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
];

// 岗位 fixture（positionsPage A-6 同口径；表单岗位选择选项源）
const POSITION_ROWS: Record<string, unknown>[] = [
  { id: '5001', positionCode: 'frontend_eng', positionName: '前端工程师', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '5002', positionCode: 'product_mgr', positionName: '产品经理', status: 'ENABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
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

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const userGets = (fetchMock: ReturnType<typeof vi.fn>) =>
  callsOf(fetchMock, 'GET', USERS_PATH_PART);

const setupUsersApi = () => {
  const state = { rows: USER_ROWS.map((row) => ({ ...row })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    // 目录单一数据源（AC-3）：deptTree/positions/roles 全量目录（fixture 同原 /depts、/positions 数据）
    if (p.includes(FORM_OPTIONS_PATH_PART)) {
      return envelope({ deptTree: DEPT_TREE, positions: POSITION_ROWS, roles: [] });
    }
    if (!p.includes(USERS_PATH_PART)) return envelope(null);
    if (r.method === 'GET') {
      // 详情端点 GET {id}（编辑回显可经详情或行数据承载，mock 双兼容，noticesPage 口径）
      const detail = p.match(/\/users\/(\d+)$/);
      if (detail) return envelope(state.rows.find((row) => row.id === detail[1]) ?? null);
      return envelope({ records: state.rows, total: state.rows.length });
    }
    if (r.method === 'PUT') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const body = parseBody(r.body);
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
  const api = setupUsersApi();
  render(<AntApp><UsersPage /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/**
 * A-14：部门筛选 Select 发现（placeholder 含「部门」），兼容两种承载：
 * showSearch 型 input[placeholder]；非搜索型 rc-select 的 .ant-select-selection-placeholder div
 * （无 placeholder 属性，mouseDown 冒泡至选择器开启下拉）。均未命中 → 抛错交 TL 裁决。
 */
const openDeptFilterDropdown = async () => {
  fireEvent.click(screen.getByRole('button', { name: '展开进阶筛选' }));
  const byInputPlaceholder = Array.from(document.querySelectorAll<HTMLInputElement>('input[placeholder]'))
    .find((el) => /部\s*门/.test(el.placeholder ?? ''));
  if (byInputPlaceholder) {
    fireEvent.mouseDown(byInputPlaceholder);
    return;
  }
  const placeholder = await waitFor(() => {
    const found = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-selection-placeholder'))
      .find((el) => /部\s*门/.test(el.textContent ?? ''));
    expect(found).toBeTruthy();
    return found as HTMLElement;
  });
  fireEvent.mouseDown(placeholder);
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

describe('REQ20261114120104 用户页部门筛选与部门/岗位表单（AC-8·用户页）', () => {
  it('should_request_user_list_with_deptid_when_dept_filter_selected', async () => {
    const { fetchMock } = await renderPage(PERM.list);
    await screen.findByText('zhangsan');

    // 部门筛选（A-14）：点选部门显示名 → 列表请求携带 deptId
    await openDeptFilterDropdown();
    await pickOption('总部');
    await waitFor(() => {
      const query = userGets(fetchMock).at(-1)?.url.searchParams;
      expect(query?.get('deptId')).toBe('4001');
    });
  });

  it('should_submit_user_update_with_dept_and_position_when_edit_confirmed', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.update]);
    const rowEl = await findRowByText('zhangsan');

    fireEvent.click(await within(rowEl).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    // 表单部门/岗位选择（A-15）：显示名点选 + 选中回显前置断言
    fireEvent.mouseDown(await within(dialog).findByLabelText('部门'));
    await pickOption('总部');
    await expectSelection(dialog, '总部');
    fireEvent.mouseDown(await within(dialog).findByLabelText('岗位'));
    await pickOption('前端工程师');
    await expectSelection(dialog, '前端工程师');
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const updates = callsOf(fetchMock, 'PUT', USERS_PATH_PART);
      expect(updates).toHaveLength(1);
      expect(updates[0]?.url.pathname).toContain('2001');
      // 提交体承载所选部门/岗位 id（键名未知 → JSON 宽松包含）
      const bodyJson = JSON.stringify(parseBody(updates[0]?.body));
      expect(bodyJson).toContain('4001');
      expect(bodyJson).toContain('5001');
    });
    // 关闭态数据驱动：提交成功后的 reload 二次 GET（071607 裁决⑪）
    await waitFor(() => {
      expect(userGets(fetchMock).length).toBeGreaterThanOrEqual(2);
    });
  });
});
