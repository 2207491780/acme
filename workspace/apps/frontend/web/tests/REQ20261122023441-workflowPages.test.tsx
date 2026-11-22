/**
 * REQ-20261122-023441 BPM-2——流程定义列表页契约用例（AC-1/AC-2 + AC-10 装配面）。
 *
 * 契约口径（contracts/REQ-20261122-023441.md AC-1/2/10 + 确认④⑥）：
 *   - AC-2：列表页 /workflow（CrudTable 简版：key/name/version/status+操作列）分页/keyword/status
 *     过滤 + 状态徽标 + 操作列分控（设计=list(78)、部署/停用=deploy(79)、新建 Drawer=deploy(79)，
 *     PermissionGate fail-closed）。
 *   - AC-1：新建 Drawer(79) 提交 key/name → POST 创建草稿（跳转设计器/空画布=X6 面，留浏览器实测）。
 *   - AC-10 装配面：部署 409 VALIDATION_FAILED → 明细经 workflowValidationErrorsOf 装配 →
 *     ValidationPanel 渲染 nodeId+message 逐条 + 点击条目回调定位（X6 highlight 标红=画布面，留浏览器实测）。
 *
 * 装配照 web/tests/REQ20261121022910-packagesPage.test.tsx 先例：fetch stubGlobal 精确 pathname
 * 路由 + useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + MemoryRouter
 * （页面 useNavigate——首个带参路由前置 /workflow/designer 确认④）。
 * 交互防坑：下拉打结构节点（pitfall 7/8）、负例消息宽容 contains（pitfall 13）、文案宽容匹配（pitfall 14）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import { ApiError, i18n, useMeStore, WORKFLOW_ERROR_CODES, workflowValidationErrorsOf } from '@acme/core';
import { WorkflowPage } from '../src/pages/WorkflowPage';
import { ValidationPanel } from '../src/pages/workflow/ValidationPanel';

vi.setConfig({ testTimeout: 45000 });

const LIST_PERM = 'workflow:definition:list';
const DEPLOY_PERM = 'workflow:definition:deploy';

// 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容；文案宽容匹配 pitfall 14）
const CREATE_BUTTON = /新\s*建\s*流\s*程/;
const DEPLOY_ACTION = /部\s*署/;
const DISABLE_ACTION = /停\s*用/;
const SUBMIT_BUTTON = /创\s*建.*进\s*入\s*设\s*计\s*器/;
const KEY_PLACEHOLDER = '如 leave-approval';
const NAME_PLACEHOLDER = '如 请假审批';
const KEYWORD_PLACEHOLDER = '按标识/名称搜索';

const PERMS_ALL = [LIST_PERM, DEPLOY_PERM];

// ————— fixtures（ProcessDefinitionView：id/version wire 字符串） —————
const DEFS = [
  {
    id: '9001', key: 'leave-approval', name: '请假审批', version: '3', status: 'DRAFT',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-21T02:00:00Z', deployedAt: null,
  },
  {
    id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-20T08:00:00Z', deployedAt: '2026-11-20T09:30:00Z',
  },
  {
    id: '9003', key: 'asset-retirement', name: '资产报废', version: '2', status: 'DISABLED',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-19T10:00:00Z', deployedAt: '2026-11-19T11:00:00Z',
  },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const asRequest = (input: RequestInfo | URL, init?: RequestInit): { url: URL; method: string; body: BodyInit | null | undefined } => {
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

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

interface WorkflowState {
  listQueries: URL[];
  posts: { body: Record<string, unknown>; url: URL }[];
  deploys: URL[];
  disables: URL[];
  rows: typeof DEFS;
}

/** 工作流定义端点 + me 精确 pathname 路由（伴随请求回空信封）。 */
const setupWorkflowApi = () => {
  const state: WorkflowState = {
    listQueries: [], posts: [], deploys: [], disables: [],
    rows: DEFS.map((row) => ({ ...row })),
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS_ALL, menus: [] });
    }
    if (p === '/admin/v1/workflow/process-definitions') {
      if (r.method === 'GET') {
        state.listQueries.push(r.url);
        const keyword = r.url.searchParams.get('keyword');
        const status = r.url.searchParams.get('status');
        const rows = state.rows.filter((row) => {
          if (keyword && !row.key.includes(keyword) && !row.name.includes(keyword)) return false;
          if (status && row.status !== status) return false;
          return true;
        });
        return envelope({ records: rows, total: rows.length });
      }
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push({ body, url: r.url });
        const created = {
          id: '9901', key: String(body.key ?? ''), name: String(body.name ?? ''), version: '1',
          status: 'DRAFT', bpmnXml: null, layoutJson: null,
          createdAt: '2026-11-22T00:00:00Z', deployedAt: null,
        };
        state.rows = [created, ...state.rows];
        return envelope(created);
      }
      return envelope(null);
    }
    const action = /^\/admin\/v1\/workflow\/process-definitions\/([^/]+)\/(deploy|disable)$/.exec(p);
    if (action) {
      const id = action[1] as string;
      if (action[2] === 'deploy') {
        state.deploys.push(r.url);
        state.rows = state.rows.map((row) => (row.id === id
          ? { ...row, status: 'DEPLOYED', deployedAt: '2026-11-22T00:00:00Z' } : row));
      } else {
        state.disables.push(r.url);
        state.rows = state.rows.map((row) => (row.id === id ? { ...row, status: 'DISABLED' } : row));
      }
      const target = state.rows.find((row) => row.id === id);
      return envelope(target ?? null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const seedMe = async (permissions: string[]): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions, menus: [],
  })));
  await useMeStore.getState().load();
};

const renderPage = async (permissions: string[] = PERMS_ALL): Promise<WorkflowState> => {
  await seedMe(permissions);
  const api = setupWorkflowApi();
  render(<AntApp><MemoryRouter><WorkflowPage /></MemoryRouter></AntApp>);
  await waitFor(() => {
    expect(screen.getByText('请假审批'), '列表应加载出交付样例行').toBeTruthy();
  });
  return api.state;
};

beforeEach(() => {
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useMeStore.getState().clear();
});

// ————— AC-2：列表页分控 + 过滤 —————

describe('REQ20261122023441 流程定义列表页（AC-2）', () => {
  it('should_render_list_with_pagination_and_filters_when_page_loaded', async () => {
    const state = await renderPage();
    // 分页首查携带 page/size（CrudTable 惯例）
    await waitFor(() => {
      expect(screen.getByText('报销申请')).toBeTruthy();
      expect(screen.getByText('资产报废')).toBeTruthy();
    });
    const firstQuery = state.listQueries[0];
    expect(firstQuery, '应有列表查询').toBeTruthy();
    expect(firstQuery.searchParams.get('page')).toBe('1');
    expect(Number(firstQuery.searchParams.get('size'))).toBeGreaterThan(0);
  });

  it('should_filter_by_keyword_when_search_input_typed', async () => {
    const state = await renderPage();
    const input = screen.getByPlaceholderText(KEYWORD_PLACEHOLDER);
    fireEvent.change(input, { target: { value: 'leave' } });
    // FilterPanel/Input.Search 语义：回车触发检索（pitfall 11 交互范式照页面既有形态）
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => {
      const last = state.listQueries[state.listQueries.length - 1];
      expect(last.searchParams.get('keyword')).toBe('leave');
    });
  });

  it('should_filter_by_status_when_status_option_picked', async () => {
    const state = await renderPage();
    // 状态筛选位于折叠的进阶筛选区（FilterPanel 既有形态）——先展开（pitfall 11）
    fireEvent.click(screen.getByText(/展开进阶筛选/));
    const panel = screen.getByRole('region', { name: '筛选条件' });
    await waitFor(() => {
      expect(panel.querySelector('.ant-select-selector'), '进阶筛选区应有状态下拉').toBeTruthy();
    });
    // 下拉交互打结构节点（pitfall 7）：容器内 .ant-select-selector 展开，浮层内选中（pitfall 8）
    const selects = panel.querySelectorAll('.ant-select-selector');
    fireEvent.mouseDown(selects[selects.length - 1] as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '状态下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('已部署'));
    await waitFor(() => {
      const last = state.listQueries[state.listQueries.length - 1];
      expect(last.searchParams.get('status')).toBe('DEPLOYED');
    });
  });

  it('should_hide_deploy_disable_and_create_actions_when_user_lacks_deploy_permission', async () => {
    // 分控（fail-closed）：仅 list(78) → 部署/停用/新建流程均不可见；设计按钮仍可见
    const state = await renderPage([LIST_PERM]);
    expect(screen.queryByText(CREATE_BUTTON)).toBeNull();
    // 操作列按钮面（避开表头「部署时间」/状态标签「已部署」等文本命中）
    expect(screen.queryAllByRole('button', { name: DEPLOY_ACTION })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: DISABLE_ACTION })).toHaveLength(0);
    expect(screen.getAllByText(/设\s*计/).length, '设计按钮=list 权限面').toBeGreaterThanOrEqual(1);
    expect(state.listQueries.length, '读面不受写分控影响').toBeGreaterThanOrEqual(1);
  });

  it('should_show_deploy_disable_and_create_actions_when_user_has_deploy_permission', async () => {
    await renderPage();
    expect(screen.getAllByRole('button', { name: DEPLOY_ACTION }).length, '部署按钮=deploy 分控').toBeGreaterThanOrEqual(1);
    expect(screen.getAllByRole('button', { name: DISABLE_ACTION }).length, '停用按钮=deploy 分控').toBeGreaterThanOrEqual(1);
    expect(screen.getByText(CREATE_BUTTON)).toBeTruthy();
    // 状态徽标（status 列文案宽文档 tolerant：zh 资源包锚点）
    expect(screen.getAllByText('已部署').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('已停用').length).toBeGreaterThanOrEqual(1);
  });
});

// ————— AC-1：新建 Drawer 提交 key/name → POST 创建草稿 —————

describe('REQ20261122023441 新建流程定义 Drawer（AC-1 载荷面）', () => {
  it('should_post_key_and_name_when_create_drawer_submitted', async () => {
    const state = await renderPage();
    fireEvent.click(screen.getByText(CREATE_BUTTON));
    // Drawer 表单（i18n 占位符为精确锚点）
    const keyInput = await waitFor(() => {
      const el = screen.getByPlaceholderText(KEY_PLACEHOLDER);
      expect(el).toBeTruthy();
      return el as HTMLInputElement;
    });
    const nameInput = screen.getByPlaceholderText(NAME_PLACEHOLDER) as HTMLInputElement;
    fireEvent.change(keyInput, { target: { value: 'it-create-req' } });
    fireEvent.change(nameInput, { target: { value: '联调演示流程' } });
    fireEvent.click(screen.getByText(SUBMIT_BUTTON));
    await waitFor(() => {
      expect(state.posts.length, 'POST 创建草稿应发出').toBe(1);
    });
    const post = state.posts[0];
    expect(post.url.pathname).toBe('/admin/v1/workflow/process-definitions');
    expect(post.body.key).toBe('it-create-req');
    expect(post.body.name).toBe('联调演示流程');
  });
});

// ————— AC-10：部署 409 明细渲染定位装配（ValidationPanel 面板面） —————

describe('REQ20261122023441 部署 409 明细装配（AC-10 装配面）', () => {
  it('should_extract_validation_details_when_deploy_returns_409', () => {
    // 装配源：ApiError(409, 01-0003, 明细随 data) → workflowValidationErrorsOf
    const error = new ApiError(409, WORKFLOW_ERROR_CODES.VALIDATION_FAILED, '流程校验未通过', [
      { nodeId: 'task-1', field: null, message: '用户任务缺少指派（assignee 或候选角色/部门/用户）' },
      { nodeId: null, field: null, message: '流程缺少结束事件' },
      { message: '' }, // 空消息条目应被过滤
      'junk', // 非对象条目应被过滤
    ]);
    const items = workflowValidationErrorsOf(error);
    expect(items.length, '仅有效明细条目入列').toBe(2);
    expect(items[0]).toEqual({ nodeId: 'task-1', field: null, message: '用户任务缺少指派（assignee 或候选角色/部门/用户）' });
    expect(items[1]?.nodeId).toBeNull();
  });

  it('should_return_empty_details_when_error_is_not_validation_failed', () => {
    // 装配负例：非 VALIDATION_FAILED / 非 ApiError → 空数组（页面回退 message 展示）
    expect(workflowValidationErrorsOf(
      new ApiError(409, WORKFLOW_ERROR_CODES.DISABLED, '已停用'),
    )).toEqual([]);
    expect(workflowValidationErrorsOf(
      new ApiError(409, WORKFLOW_ERROR_CODES.VALIDATION_FAILED, '无明细', undefined),
    )).toEqual([]);
    expect(workflowValidationErrorsOf(new Error('plain'))).toEqual([]);
  });

  it('should_render_node_messages_and_invoke_locate_when_item_clicked', async () => {
    const onLocate = vi.fn();
    const items = [
      { nodeId: 'task-1', message: '用户任务缺少指派（assignee 或候选角色/部门/用户）' },
      { nodeId: null, message: '流程缺少结束事件' },
    ];
    render(<AntApp><ValidationPanel status="backend" items={items} onLocate={onLocate} sourceLabel="后端校验明细" /></AntApp>);
    // 后端明细逐条渲染（nodeId+message）
    expect(await screen.findByText(/task-1/)).toBeTruthy();
    expect(screen.getByText('用户任务缺少指派（assignee 或候选角色/部门/用户）')).toBeTruthy();
    expect(screen.getByText('流程缺少结束事件')).toBeTruthy();
    expect(screen.getByText('后端校验明细')).toBeTruthy();
    // 点击条目「定位」按钮 → 定位回调（画布 X6 highlight 标红=浏览器实测项）
    const row = screen.getByText(/task-1/).closest('li') as HTMLElement;
    expect(row, '明细条目应可定位到行容器').toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: '定位' }));
    await waitFor(() => {
      expect(onLocate).toHaveBeenCalledWith('task-1');
    });
    expect(onLocate).toHaveBeenCalledTimes(1);
  });

  it('should_render_pass_state_when_no_violations_found', () => {
    render(<AntApp><ValidationPanel status="pass" items={[]}/></AntApp>);
    // 无问题提示通过（AC-9 面板文案族；标签与正文均命中，宽容多节点）
    expect(screen.getAllByText(/静态校验通过/).length).toBeGreaterThanOrEqual(1);
  });
});

// i18n 触达（避免 tree-shake 语义盲区；资源键归 frontend，文案宽容匹配口径）
void i18n;
