/**
 * REQ-20261114-120104 AC-8（前端面·部门树）：DeptsPage 树形渲染（父子层级、含禁用态）/
 * 新建（选父级）/编辑回填/删除守卫反馈（DEPT_HAS_CHILDREN/DEPT_HAS_USERS 转译文案）/权限显隐（fail-closed）。
 *
 * 装配照既有范本 web/tests/REQ20261114120102-noticesPage.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式播种 + <AntApp> 包裹 + testTimeout ≥15000ms + 关闭态断言数据驱动；
 * 树表口径参照 REQ20261114120101-crudPages 的 MenusPage 套件（paged=false 树表）。
 *
 * 【表面假定（REQ-20261114-120104 系列 A-1..，汇总见交付说明；待 TL 裁决）】
 *   A-1 页面 web/src/pages/DeptsPage.tsx 命名导出 DeptsPage，自取数据零必填 props（crudPages A-35 口径）。
 *   A-2 DeptView wire 形状：id/parentId/deptName/sortOrder/status(ENABLED|DISABLED)/children/version，
 *       camelCase、id 字符串（对齐 AdminMenuView 命名惯例）；GET /admin/v1/system/depts 按契约
 *       「树形返回」字面口径以 data=树数组 mock——若实现取 {records,total} 分页信封，提请 TL 裁决改 mock。
 *   A-3 DeptsPage 为树表：父子层级经行展开控件（.ant-table-row-expand-icon）呈现；
 *       子行默认展开与否不做假定（先探子行可见，不可见再点展开，两种实现下断言等价）。
 *   A-4 表单字段 label：「部门名称」（Input 必填）、「上级部门」（Select 选父级）；
 *       排序/状态字段不做交互（默认值可直接提交；状态显示名未知不猜）。
 *   A-5 删除守卫转译文案未知 → 宽松正则 /子部门|DEPT_HAS_CHILDREN/、/用户|DEPT_HAS_USERS/
 *       （filesPage A-29 口径），断言收敛 .ant-message 容器。
 *   A-15 下拉点选收敛：经开放下拉容器（.ant-select-dropdown:not(.ant-select-dropdown-hidden)）
 *       within().findByText(显示名) 点选——仍为显示名文本点选（委派单口径、禁 role=option 探针），
 *       容器收敛防「表格行与选项同文案」多匹配（总部行 ↔ 上级部门选项撞车）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { DeptsPage } from '../src/pages/DeptsPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const MSG_HAS_CHILDREN = /子部门|DEPT_HAS_CHILDREN/; // A-5
const MSG_HAS_USERS = /用户|DEPT_HAS_USERS/; // A-5

const PERM = {
  list: ['system:dept:list'],
  create: ['system:dept:create'],
  update: ['system:dept:update'],
  delete: ['system:dept:delete'],
};

const DEPTS_PATH_PART = '/depts';

type DeptNode = {
  id: string;
  parentId: string;
  deptName: string;
  sortOrder: number;
  status: 'ENABLED' | 'DISABLED';
  children: DeptNode[];
  version: string;
};

/**
 * fixtures（A-2 wire 形状）：总部(4001)↘研发部(4002) 承载父子层级；财务部(4004) 承载禁用态；
 * 市场部(4003) 为可删叶子；总部/财务部分别 mock 为 DEPT_HAS_CHILDREN/DEPT_HAS_USERS 拒删目标。
 */
const DEPT_TREE: DeptNode[] = [
  {
    id: '4001', parentId: '0', deptName: '总部', sortOrder: 1, status: 'ENABLED', version: '0',
    children: [
      { id: '4002', parentId: '4001', deptName: '研发部', sortOrder: 1, status: 'ENABLED', version: '0', children: [] },
    ],
  },
  { id: '4003', parentId: '0', deptName: '市场部', sortOrder: 2, status: 'ENABLED', version: '0', children: [] },
  { id: '4004', parentId: '0', deptName: '财务部', sortOrder: 3, status: 'DISABLED', version: '0', children: [] },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
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
    .filter((r) => r.url.pathname.includes(DEPTS_PATH_PART) && r.method === method);

const cloneTree = (nodes: DeptNode[]): DeptNode[] =>
  nodes.map((node) => ({ ...node, children: cloneTree(node.children) }));

const removeFromTree = (nodes: DeptNode[], id: string): DeptNode[] =>
  nodes
    .filter((node) => node.id !== id)
    .map((node) => ({ ...node, children: removeFromTree(node.children, id) }));

const patchInTree = (nodes: DeptNode[], id: string, patch: Record<string, unknown>): DeptNode[] =>
  nodes.map((node) => (node.id === id
    ? ({ ...node, ...patch } as DeptNode)
    : { ...node, children: patchInTree(node.children, id, patch) }));

const setupDeptsApi = () => {
  const state = { tree: cloneTree(DEPT_TREE) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes(DEPTS_PATH_PART)) return envelope(null);
    if (r.method === 'GET') return envelope(state.tree); // A-2：树数组字面口径
    if (r.method === 'POST') {
      const body = parseBody(r.body);
      return envelope({ id: '9901', parentId: '0', sortOrder: 0, status: 'ENABLED', version: '0', children: [], ...body });
      // 不插入树：reload 返回既有 fixture，成功关闭态以二次 GET 数据驱动断言
    }
    if (r.method === 'PUT') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const body = parseBody(r.body);
      state.tree = patchInTree(state.tree, id, body);
      const found = (function find(nodes: DeptNode[]): DeptNode | null {
        for (const node of nodes) {
          if (node.id === id) return node;
          const hit = find(node.children);
          if (hit) return hit;
        }
        return null;
      })(state.tree);
      return envelope(found);
    }
    if (r.method === 'DELETE') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      // 删除守卫（契约确认记录③）：有子部门 → DEPT_HAS_CHILDREN；有用户 → DEPT_HAS_USERS
      if (id === '4001') return failureEnvelope('DEPT_HAS_CHILDREN', '存在子部门，无法删除');
      if (id === '4004') return failureEnvelope('DEPT_HAS_USERS', '部门下存在用户，无法删除');
      state.tree = removeFromTree(state.tree, id);
      return envelope(null);
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
  const api = setupDeptsApi();
  render(<AntApp><DeptsPage /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** A-3：子行可见性不做默认展开假定——先探可见，不可见再点行展开控件 */
const ensureChildVisible = async (parentAnchor: string, childAnchor: string) => {
  const direct = await screen.findByText(childAnchor, {}, { timeout: 2000 }).catch(() => null);
  if (direct) return direct;
  const rowEl = await findRowByText(parentAnchor);
  const expandIcon = rowEl.querySelector('.ant-table-row-expand-icon');
  if (expandIcon) fireEvent.click(expandIcon);
  return screen.findByText(childAnchor);
};

/** 对话框内文本类输入框（Input/TextArea/number；排除 select 搜索框/checkbox/hidden，A-36 口径） */
const dialogTextboxes = (dialog: HTMLElement) =>
  Array.from(dialog.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')).filter(
    (el) => !el.disabled && !el.readOnly
      && (el.type === 'text' || el.type === '' || el.type === 'number' || el.tagName === 'TEXTAREA'),
  );

/** A-15：点选当前开放下拉容器内的显示名选项（禁 role=option 探针，防表格行同文案撞车） */
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

describe('REQ20261114120104 部门管理页 DeptsPage（AC-8·部门树）', () => {
  it('should_render_dept_tree_with_parent_child_hierarchy_when_page_loads', async () => {
    await renderPage(PERM.list);

    // 根级节点与禁用态节点渲染（A-2 含禁用态）
    expect(await screen.findByText('总部')).toBeTruthy();
    expect(screen.getByText('市场部')).toBeTruthy();
    expect(screen.getByText('财务部')).toBeTruthy();
    // 父子层级：总部下可见研发部（A-3，默认展开与否均等价）
    await ensureChildVisible('总部', '研发部');
  });

  it('should_submit_create_with_selected_parent_when_add_confirmed', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.create]);
    await screen.findByText('总部');

    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('部门名称'), { target: { value: '人事部' } });
    // 选父级（A-4）：上级部门 Select 显示名点选
    fireEvent.mouseDown(within(dialog).getByLabelText('上级部门'));
    await pickOption('总部');
    await expectSelection(dialog, '总部');
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    // 提交体承载名称与父级 id（键名未知 → JSON 宽松包含，A-4）
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST');
      expect(posts).toHaveLength(1);
      const bodyJson = JSON.stringify(parseBody(posts[0]?.body));
      expect(bodyJson).toContain('人事部');
      expect(bodyJson).toContain('4001');
    });
    // 关闭态数据驱动：提交成功后的 reload 二次 GET（071607 裁决⑪）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_prefill_and_submit_update_when_row_edit_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.update]);
    const rowEl = await findRowByText('市场部');

    fireEvent.click(await within(rowEl).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');
    const box = await waitFor(() => {
      const found = dialogTextboxes(dialog).find((el) => el.value === '市场部');
      expect(found).toBeTruthy();
      return found as HTMLInputElement | HTMLTextAreaElement;
    });
    fireEvent.change(box, { target: { value: '市场部改名' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const updates = callsOf(fetchMock, 'PUT');
      expect(updates).toHaveLength(1);
      expect(updates[0]?.url.pathname).toContain('4003');
      expect(JSON.stringify(parseBody(updates[0]?.body))).toContain('市场部改名');
    });
  });

  it('should_keep_row_and_show_translated_feedback_when_delete_rejected_for_children', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('总部'); // 有子部门（研发部）→ DEPT_HAS_CHILDREN

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(callsOf(fetchMock, 'DELETE')).toHaveLength(1));
    // 统一转译反馈（文案未知 → 宽松正则，A-5）：收敛 antd message 容器
    await waitFor(() => {
      expect(document.querySelector('.ant-message')?.textContent ?? '').toMatch(MSG_HAS_CHILDREN);
    });
    // 行保留（数据驱动：未删除；子行可见性走 A-3 探测，默认展开与否均等价）
    expect(screen.getByText('总部')).toBeTruthy();
    await ensureChildVisible('总部', '研发部');
  });

  it('should_keep_row_and_show_translated_feedback_when_delete_rejected_for_users', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('财务部'); // 部门下有用户 → DEPT_HAS_USERS

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(callsOf(fetchMock, 'DELETE')).toHaveLength(1));
    await waitFor(() => {
      expect(document.querySelector('.ant-message')?.textContent ?? '').toMatch(MSG_HAS_USERS);
    });
    expect(screen.getByText('财务部')).toBeTruthy();
  });

  it('should_delete_leaf_dept_after_confirm_when_row_delete_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('市场部'); // 无子无用户的叶子可直接删

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.pathname).toContain('4003');
    });
    await waitFor(() => expect(screen.queryByText('市场部')).toBeNull());
    expect(screen.getByText('总部')).toBeTruthy();
  });

  it('should_hide_unauthorized_actions_when_dept_permissions_missing', async () => {
    await renderPage(PERM.list); // 仅 list 权限（fail-closed）
    await screen.findByText('总部');

    expect(screen.queryByText(ADD_BUTTON)).toBeNull();
    const rowEl = await findRowByText('总部');
    expect(within(rowEl).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(rowEl).queryByText(DELETE_BUTTON)).toBeNull();
    const leafRow = await findRowByText('市场部');
    expect(within(leafRow).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(leafRow).queryByText(DELETE_BUTTON)).toBeNull();
  });
});
