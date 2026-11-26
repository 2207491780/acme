/** @vitest-environment jsdom */
/**
 * REQ-20261125-230807 AC-1/AC-2/AC-3/AC-27（用户侧）—— UsersPage 目录数据源契约用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-1       用户列表新增「部门」「岗位」两列（名称映射自目录，空值显示 —）。
 *   AC-2       新建用户表单：部门=树形下拉（层级展示、支持搜索）、选中回显层级路径、
 *              选项文本无前导空格缩进、提交 deptId 字符串原样。
 *   AC-3       仅 system:user:list → 目录走 GET /admin/v1/system/user-form-options，
 *              不再消费 dept:list/position:list/角色分页；筛选下拉数据齐全。
 *   AC-27      目录加载失败 → 可见警告 + 重试入口（重试成功后候选项恢复）。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 权限播种 + <AntApp> 包裹 + jsdom 环境头 + testTimeout ≥15000ms；下拉交互照 pitfalls
 * 卡 7/8（.ant-select-* 结构节点 + 浮层容器内按文本选中）；文案宽容匹配（pitfall 14）。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 AdminUserView 增 deptId/positionId（契约注释「无名称字段、前端映射口径」→ 行内渲染目录名）。
 *   A-2 表单 label 文案按域通用词（用户名/昵称/部门/岗位/角色）宽容定位；层级路径分隔符按契约示例
 *       「研发中心 / 平台组」（空格斜杠空格）宽容正则匹配。
 *   A-3 实现未落地属预期红（委派单钉死事实⑥）：form-options 端点/两列/树形回显均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { UsersPage } from '../src/pages/UsersPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

const SUBMIT_BUTTON = /提\s*交|确\s*定|^OK$/i;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

// ————— fetch 边界 mock（范本 helpers 照抄） —————
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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> | null => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return null;
  }
};

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

// ————— 装配（范本口径照抄） —————
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

const seedMe = async (permissions: string[]): Promise<void> => {
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

const renderPage = async (Component: ComponentType, permissions: string[], routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  await seedMe(permissions);
  const api = routes ? setupApi(routes) : setupApi({});
  render(<AntApp><Component /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/**
 * 定位 label 命中候选词之一的下拉控件（FilterField/antd Form 通用）：
 * 优先 label[for]→#id→.ant-select（UI-SPEC FilterField 契约：htmlFor 关联控件）；
 * 兜底 label 最近容器内 .ant-select（antd Form .ant-form-item）。返回 { item, select }。
 */
const findSelectByLabels = async (scope: HTMLElement | Document, labels: string[]): Promise<{ item: HTMLElement; select: HTMLElement }> => {
  await waitFor(() => {
    const found = labels.some((label) => Array.from(scope.querySelectorAll('label')).some((el) => new RegExp(label).test(el.textContent ?? '')));
    expect(found, `应存在 label 命中 ${labels.join('/')}`).toBe(true);
  });
  for (const label of labels) {
    const labelEls = Array.from(scope.querySelectorAll('label')).filter((el) => new RegExp(label).test(el.textContent ?? ''));
    for (const el of labelEls) {
      const htmlFor = el.getAttribute('for');
      if (htmlFor) {
        const control = document.getElementById(htmlFor);
        const selectRoot = control?.closest('.ant-select') as HTMLElement | null;
        if (selectRoot) {
          return { item: (selectRoot.closest('.ant-form-item') ?? selectRoot.parentElement ?? selectRoot) as HTMLElement, select: selectRoot };
        }
      }
      const item = (el.closest('.ant-form-item, [class*="filter-field"], .ant-space-item, td, th') ?? el.parentElement) as HTMLElement | null;
      const select = item?.querySelector('.ant-select') as HTMLElement | null;
      if (select) return { item: item as HTMLElement, select };
    }
  }
  throw new Error(`label ${labels.join('/')} 对应下拉控件未找到（红则 TL 裁决）`);
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

// ————— fixtures（契约 §API：UserFormOptionsView + AdminUserView 增 deptId/positionId（A-1）；id wire string） —————
const SF_DEPT_ROOT = '9223372036854775811';
const SF_DEPT_LEAF = '9223372036854775812';
const SF_DEPT_OTHER = '9223372036854775813';
const SF_POS_1 = '9223372036854775821';
const SF_POS_2 = '9223372036854775822';
const SF_ROLE_1 = '9223372036854775831';
const SF_ROLE_2 = '9223372036854775832';

const DEPT_TREE = [
  {
    id: SF_DEPT_ROOT, parentId: null, deptName: '研发中心', sortOrder: 1, status: 'ENABLED', version: '0',
    children: [{ id: SF_DEPT_LEAF, parentId: SF_DEPT_ROOT, deptName: '平台组', sortOrder: 1, status: 'ENABLED', version: '0', children: [] }],
  },
  { id: SF_DEPT_OTHER, parentId: null, deptName: '市场部', sortOrder: 2, status: 'ENABLED', version: '0', children: [] },
];

const POSITION_ROWS = [
  { id: SF_POS_1, positionCode: 'frontend_eng', positionName: '前端工程师', status: 'ENABLED', version: '0' },
  { id: SF_POS_2, positionCode: 'product_mgr', positionName: '产品经理', status: 'ENABLED', version: '0' },
];

const ROLE_ROWS = [
  { id: SF_ROLE_1, parentId: null, roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', version: '0', children: [] },
  { id: SF_ROLE_2, parentId: null, roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', version: '0', children: [] },
];

const FORM_OPTIONS = { deptTree: DEPT_TREE, positions: POSITION_ROWS, roles: ROLE_ROWS };

const USER_ROWS = [
  { id: '2001', username: 'alice', nickname: '爱丽丝', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [SF_ROLE_2], deptId: SF_DEPT_ROOT, positionId: SF_POS_1, createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
  { id: '2002', username: 'bob', nickname: '鲍勃', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], deptId: null, positionId: null, createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
];

const usersRoutes = (dirState: { fail: boolean } = { fail: false }) => ({
  // 长前缀先匹配：GET /admin/v1/system/user-form-options（新增目录读端点）
  '/system/user-form-options': () => (dirState.fail
    ? failureEnvelope('INTERNAL_ERROR', '服务器内部错误')
    : envelope(FORM_OPTIONS)),
  '/system/users': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: USER_ROWS, total: USER_ROWS.length });
    if (r.method === 'POST') return envelope(USER_ROWS[0]);
    return envelope(null);
  },
  // 旧口径目录端点（修复后该页不应再消费——AC-3 消费面切换钉死）
  '/system/depts': () => envelope(DEPT_TREE),
  '/system/positions': () => envelope({ records: POSITION_ROWS, total: POSITION_ROWS.length }),
  '/system/roles': () => envelope({ records: ROLE_ROWS, total: ROLE_ROWS.length }),
});

// ————— AC-1：用户列表部门/岗位两列 —————
describe('REQ20261125230807 UsersPage 部门/岗位列（AC-1）', () => {
  it('should_render_dept_and_position_names_from_directory_when_user_rows_have_ids', async () => {
    await renderPage(UsersPage, ['system:user:list'], usersRoutes());
    const aliceRow = await findRowByText('alice');
    // 两列标题存在 + 行内渲染目录名称（映射自 form-options，非 ID 直显——A-1）
    await waitFor(() => {
      const head = document.querySelector('thead');
      expect(head?.textContent ?? '').toContain('部门');
      expect(head?.textContent ?? '').toContain('岗位');
    });
    expect(aliceRow.textContent, '部门列应显示目录名「研发中心」').toContain('研发中心');
    expect(aliceRow.textContent, '岗位列应显示目录名「前端工程师」').toContain('前端工程师');
    // 空值显示 —（bob deptId/positionId 均空）
    const bobRow = await findRowByText('bob');
    expect(bobRow.textContent, '空 deptId/positionId 应显示 —').toContain('—');
  });
});

// ————— AC-2：用户表单树形部门选择 + 字符串 deptId 提交 —————
describe('REQ20261125230807 UsersPage 部门树形下拉（AC-2）', () => {
  it('should_show_tree_dept_select_with_search_and_hierarchy_when_user_form_opened', async () => {
    await renderPage(UsersPage, ['system:user:list', 'system:user:create'], usersRoutes());
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建/ }));
    const dialog = await screen.findByRole('dialog');

    // 树形下拉：层级展示（子节点有树缩进结构）+ 选项文本无前导空格缩进
    const { select } = await findSelectByLabels(dialog, ['部门']);
    fireEvent.mouseDown(select.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '部门下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    const leafTitle = await within(dropdown).findByText('平台组');
    expect(leafTitle.textContent, '选项文本不得带前导空格缩进').toBe('平台组');
    const treeNode = (leafTitle.closest('[class*="tree-treenode"]') ?? leafTitle.closest('li')) as HTMLElement | null;
    expect(treeNode?.querySelector('[class*="tree-indent"]'), '子节点应有层级缩进结构（树形展示）').toBeTruthy();

    // 支持搜索：输入关键字后无关节点收敛
    const searchInput = select.querySelector('.ant-select-selection-search-input, .ant-select-selector input') as HTMLInputElement | null;
    expect(searchInput, '部门选择应支持搜索（showSearch）').toBeTruthy();
    fireEvent.change(searchInput!, { target: { value: '平台' } });
    await waitFor(() => {
      expect(within(dropdown).queryByText('市场部'), '搜索应过滤无关节点').toBeNull();
    });

    // 选中后表单回显层级路径（「研发中心 / 平台组」形态，宽容空白）——现状实现仅回显叶子名 → 预期红
    fireEvent.click(leafTitle);
    await waitFor(() => {
      expect((select.querySelector('.ant-select-selector') as HTMLElement).textContent ?? '')
        .toMatch(/研发中心\s*\/\s*平台组/);
    });
  });

  it('should_submit_raw_string_deptid_when_user_form_submitted', async () => {
    const { fetchMock } = await renderPage(UsersPage, ['system:user:list', 'system:user:create'], usersRoutes());
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建/ }));
    const dialog = await screen.findByRole('dialog');

    // 选中平台组（子节点）后提交 → POST body deptId 雪花字符串原样（AC-4 页面半句同口径）
    const { select } = await findSelectByLabels(dialog, ['部门']);
    fireEvent.mouseDown(select.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '部门下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(await within(dropdown).findByText('平台组'));

    fireEvent.change(within(dialog).getByLabelText(/账号|用户名/), { target: { value: 'newuser' } });
    fireEvent.change(within(dialog).getByLabelText(/昵称/), { target: { value: '新用户' } });
    const { select: roleSelect } = await findSelectByLabels(dialog, ['角色']);
    fireEvent.mouseDown(roleSelect.querySelector('.ant-select-selector') as Element);
    const roleDropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '角色下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(roleDropdown).getByText('运维员'));

    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST', '/admin/v1/system/users');
      expect(posts).toHaveLength(1);
      const body = parseBody(posts[0]?.body) ?? {};
      expect(body.deptId, 'deptId 应为雪花字符串原样（不得 Number 数值化）').toBe(SF_DEPT_LEAF);
    });
  });
});

// ————— AC-3：仅 system:user:list → 目录走 user-form-options —————
describe('REQ20261125230807 UsersPage 目录端点消费切换（AC-3）', () => {
  it('should_load_directory_via_form_options_and_skip_legacy_endpoints_when_only_user_list_granted', async () => {
    const { fetchMock } = await renderPage(UsersPage, ['system:user:list'], usersRoutes());
    await findRowByText('alice');
    // 目录全量走新端点
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/user-form-options').length).toBeGreaterThanOrEqual(1);
    });
    // 消费面切换：仅有 system:user:list 时 dept:list/position:list/角色分页会 403 → 不再调用
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/depts'), '不应再消费 dept:list').toHaveLength(0);
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/positions'), '不应再消费 position:list').toHaveLength(0);
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles'), '不应再消费角色分页').toHaveLength(0);

    // 筛选区部门下拉数据齐全（来自 form-options）
    const { select: filterSelectA } = await findSelectByLabels(document, ['部门']);
    fireEvent.mouseDown(filterSelectA.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '筛选部门下拉应展开').toBeTruthy();
      return el as HTMLElement;
    });
    expect(await within(dropdown).findByText('研发中心')).toBeTruthy();
    expect(await within(dropdown).findByText('平台组')).toBeTruthy();
  });
});

// ————— AC-27（用户侧）：目录加载失败可见警告 + 重试 —————
describe('REQ20261125230807 目录失败可见化（AC-27 用户侧）', () => {
  it('should_show_visible_warning_with_retry_when_directory_load_fails', async () => {
    const dirState = { fail: true };
    const { fetchMock } = await renderPage(UsersPage, ['system:user:list'], usersRoutes(dirState));
    await findRowByText('alice');

    // 可见警告而非静默空选项
    await waitFor(() => {
      const alert = document.querySelector('.ant-alert-error, .ant-alert-warning, [role="alert"]');
      expect(alert, '目录加载失败应出现可见警告').toBeTruthy();
    });
    // 重试入口：翻转 mock 为成功 → 点击重试 → 目录重新拉取且候选项恢复
    const retry = await screen.findByRole('button', { name: /重\s*试/ });
    dirState.fail = false;
    fireEvent.click(retry);
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/user-form-options').length).toBeGreaterThanOrEqual(2);
    });
    const { select: filterSelectB } = await findSelectByLabels(document, ['部门']);
    fireEvent.mouseDown(filterSelectB.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '重试成功后下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    expect(await within(dropdown).findByText('研发中心')).toBeTruthy();
  });
});
