// @vitest-environment jsdom
/**
 * REQ-20261122-102730 BPM-3——流程管理页/待办中心页契约用例（AC-1~AC-8 页面装配面）。
 *
 * 契约口径（contracts/REQ-20261122-102730.md 确认②③④⑦ + AC-1~8）：
 *   - AC-1：/workflow 三 Tab（流程定义/实例监控/待办中心入口提示）+ 既有定义 Tab 零回归；
 *   - AC-2/4：实例监控 Tab 分页列表 definitionKey/status/businessKey 过滤 + 状态徽标 +
 *     行操作按状态分控（RUNNING→挂起/终止；SUSPENDED→恢复；Popconfirm）+ 权限分控(80)；
 *   - AC-3：实例详情 Drawer 五块信息架构（摘要/活跃节点+活动任务/变量摘要/历史时间线/流程图）
 *     + 历史时间线 action 分色（label 面）；
 *   - AC-5：/workflow/todos 我的待办/我发起两子区 + 分页 + 列表含投影列
 *     （definitionName/businessKey/initiator，契约⑤禁前端 N+1）；
 *   - AC-6/7/8：认领/办理（complete 表单）/驳回（targetNodeId 选择）/转办/委托操作面。
 *
 * 装配照 web/tests/REQ20261122023441-workflowPages.test.tsx 先例：fetch stubGlobal 精确 pathname
 * 路由 + useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + MemoryRouter。
 * 交互防坑（pitfalls 卡 7/8/10/11/13/14）：下拉打 .ant-select-* 结构节点并在浮层容器内选中文案；
 * 两字按钮 autoInsertSpace 用 \s* 宽容正则；负例/文案宽容匹配。
 * readOnly 流程图 activeNodes 高亮/REJECT_EDGE 边标红/X6 只读装配=X6 浏览器实测项（确认⑨，TL 走查），
 * 本文件只断言 Drawer 各块装配与历史时间线文案面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import { i18n, useMeStore } from '@acme/core';
import { WorkflowPage } from '../src/pages/WorkflowPage';
import { TodoCenterPage } from '../src/pages/TodoCenterPage';

vi.setConfig({ testTimeout: 45000 });

// X6 链路隔离：WorkflowPage→InstanceMonitorTab→canvasFactory 静态拉入 @antv/x6，
// x6-common polyfill 在 jsdom 收集期抛 Element is not defined。画布装配（createBpmnCanvas/
// renderReadOnlyBpmn/applyCanvasTheme）以桩替换——readOnly 流程图 activeNodes 高亮/REJECT_EDGE
// 边标红=X6 浏览器实测项（确认⑨，TL 走查），本文件只断言 Drawer 各块装配面。
vi.mock('../src/pages/workflow/canvasFactory', () => ({
  createBpmnCanvas: vi.fn(() => {
    throw new Error('jsdom 不渲染画布（X6 浏览器实测项）');
  }),
  renderReadOnlyBpmn: vi.fn(),
  applyCanvasTheme: vi.fn(),
}));

const LIST_PERM = 'workflow:definition:list';
const DEPLOY_PERM = 'workflow:definition:deploy';
const INSTANCE_PERM = 'workflow:instance:manage';
const TASK_LIST_PERM = 'workflow:task:list';
const TASK_OPERATE_PERM = 'workflow:task:operate';
const PERMS_ALL = [LIST_PERM, DEPLOY_PERM, INSTANCE_PERM, TASK_LIST_PERM, TASK_OPERATE_PERM];

// 行为常量（i18n zh-CN workflow 资源包锚点；两字按钮 autoInsertSpace 宽容匹配）
const TAB_DEFS = '流程定义';
const TAB_INSTANCES = '实例监控';
const TAB_TODOS = '待办中心';
const TODO_GO = /前\s*往\s*待\s*办\s*中\s*心/;
const SEG_TODO = '我的待办';
const SEG_INITIATED = '我发起';
const ACTION_DETAIL = /详\s*情/;
const ACTION_SUSPEND = /^挂\s*起$/;
const ACTION_TERMINATE = /^终\s*止$/;
const ACTION_RESUME = /^恢\s*复$/;
const ACTION_CLAIM = /^认\s*领$/;
const ACTION_UNCLAIM = /^取消认领$/;
const ACTION_COMPLETE = /^办\s*理$/;
const ACTION_TRANSFER = /^转\s*办$/;
const ACTION_DELEGATE = /^委\s*托$/;
const DRAWER_INSTANCE = '实例详情';
const DRAWER_TASK = '任务详情';
const MODAL_TRANSFER = '转办任务';
const MODAL_DELEGATE = '委托任务';
const SUBMIT_COMPLETE = /提\s*交\s*办\s*结/;
const SUBMIT_REJECT = /确\s*认\s*驳\s*回/;
const SUBMIT_ASSIGNEE = /^确\s*认$/;
const COMMENT_PLACEHOLDER = '填写办理意见（可选）';
const ASSIGNEE_PLACEHOLDER = '如 ops';
const STATUS_RUNNING = '运行中';
const STATUS_SUSPENDED = '已挂起';
const STATUS_TERMINATED = '已终止';
const STATUS_COMPLETED = '已完成';
const TASK_STATUS_CLAIMED = '已认领';

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

// ————— fixtures（契约形状：wire 字符串 id；实例覆盖状态机四态，任务覆盖 CREATED/CLAIMED/COMPLETED） —————

const DEFS = [
  { id: '9001', key: 'leave-approval', name: '请假审批', version: '3', status: 'DRAFT',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-21T02:00:00Z', deployedAt: null },
  { id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-20T08:00:00Z', deployedAt: '2026-11-20T09:30:00Z' },
  { id: '9003', key: 'asset-retirement', name: '资产报废', version: '2', status: 'DISABLED',
    bpmnXml: null, layoutJson: null, createdAt: '2026-11-19T10:00:00Z', deployedAt: '2026-11-19T11:00:00Z' },
];

interface InstanceRow {
  id: string; definitionId: string; definitionKey: string | null; definitionName: string | null;
  businessKey: string | null; initiator: string | null;
  status: 'RUNNING' | 'SUSPENDED' | 'COMPLETED' | 'TERMINATED';
  suspendReason: string | null; startTime: string; endTime: string | null;
  variables: Record<string, unknown> | null;
  activeNodes: { nodeId: string; nodeName?: string | null; nodeType?: string | null; enterAt?: string | null }[];
  tasks: { id: string; instanceId: string; nodeId: string; nodeName: string | null; assignee: string | null;
    status: string; claimTime: string | null; completeTime: string | null; createTime: string }[];
}

const baseInstance = (over: Partial<InstanceRow> & { id: string; businessKey: string; initiator: string; status: InstanceRow['status'] }): InstanceRow => ({
  definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
  suspendReason: null, startTime: '2026-11-22T01:30:00Z', endTime: null,
  variables: null, activeNodes: [], tasks: [], ...over,
});

const INSTANCES_SEED: InstanceRow[] = [
  baseInstance({
    id: '9301', businessKey: 'EXP-20261122-001', initiator: 'admin', status: 'RUNNING',
    variables: { amount: 1280, reason: '差旅报销' },
    activeNodes: [{ nodeId: 'task-1', nodeName: '主管审批', nodeType: 'UserTask', enterAt: '2026-11-22T01:30:05Z' }],
    tasks: [{ id: '9401', instanceId: '9301', nodeId: 'task-1', nodeName: '主管审批', assignee: null,
      status: 'CREATED', claimTime: null, completeTime: null, createTime: '2026-11-22T01:30:05Z' }],
  }),
  baseInstance({ id: '9302', businessKey: 'EXP-20261122-002', initiator: 'ops', status: 'RUNNING', startTime: '2026-11-22T02:40:00Z' }),
  baseInstance({
    id: '9303', businessKey: 'EXP-20261122-003', initiator: 'admin', status: 'SUSPENDED',
    suspendReason: 'SERVICE_TASK_FAILED', startTime: '2026-11-22T00:15:00Z',
  }),
  baseInstance({
    id: '9304', businessKey: 'EXP-20261121-001', initiator: 'admin', status: 'COMPLETED',
    startTime: '2026-11-21T09:30:00Z', endTime: '2026-11-21T11:05:00Z',
  }),
  baseInstance({
    id: '9305', businessKey: 'EXP-20261120-009', initiator: 'ops', status: 'TERMINATED',
    startTime: '2026-11-20T14:00:00Z', endTime: '2026-11-20T16:30:00Z',
  }),
];

interface TaskRow {
  id: string; instanceId: string; nodeId: string; nodeName: string | null; assignee: string | null;
  status: 'CREATED' | 'CLAIMED' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';
  claimTime: string | null; completeTime: string | null; createTime: string;
  definitionKey: string | null; definitionName: string | null; businessKey: string | null; initiator: string | null;
}

const baseTask = (over: Partial<TaskRow> & { id: string; instanceId: string; businessKey: string; initiator: string; status: TaskRow['status'] }): TaskRow => ({
  nodeId: 'task-1', nodeName: '主管审批', assignee: null, claimTime: null, completeTime: null,
  createTime: '2026-11-22T01:30:05Z', definitionKey: 'expense-claim', definitionName: '报销申请', ...over,
});

const TASKS_SEED: TaskRow[] = [
  baseTask({ id: '9401', instanceId: '9301', businessKey: 'EXP-20261122-001', initiator: 'admin', status: 'CREATED' }),
  baseTask({
    id: '9402', instanceId: '9302', businessKey: 'EXP-20261122-002', initiator: 'ops', status: 'CLAIMED',
    assignee: 'admin', claimTime: '2026-11-22T03:10:00Z', createTime: '2026-11-22T02:40:00Z',
  }),
  baseTask({
    id: '9403', instanceId: '9304', businessKey: 'EXP-20261121-001', initiator: 'admin', status: 'COMPLETED',
    assignee: 'admin', claimTime: '2026-11-21T10:00:00Z', completeTime: '2026-11-21T11:00:00Z',
    createTime: '2026-11-21T09:30:00Z',
  }),
];

const HISTORY_9301 = [
  { id: '9601', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T01:30:00Z', endAt: null, detail: null },
  { id: '9602', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-22T01:30:02Z', endAt: '2026-11-22T01:30:04Z', detail: null },
  { id: '9603', nodeId: 'flow-2', nodeType: 'sequenceFlow', action: 'REJECT_EDGE', assignee: 'ops', startAt: '2026-11-22T01:45:00Z', endAt: null, detail: '驳回：金额超限回流' },
  { id: '9604', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: null, startAt: '2026-11-22T01:30:05Z', endAt: null, detail: null },
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

interface WorkflowApiState {
  defQueries: URL[];
  instanceQueries: URL[];
  initiatedQueries: URL[];
  detailQueries: URL[];
  historyQueries: URL[];
  todoQueries: URL[];
  doneQueries: URL[];
  instancePosts: { url: URL; body: Record<string, unknown> }[];
  taskPosts: { url: URL; body: Record<string, unknown> }[];
  instances: InstanceRow[];
  tasks: TaskRow[];
}

/** 工作流实例/任务域 + 定义域 + me 精确 pathname 路由；行操作按状态机迁移并记录调用。 */
const setupWorkflowApi = () => {
  const state: WorkflowApiState = {
    defQueries: [], instanceQueries: [], initiatedQueries: [], detailQueries: [], historyQueries: [],
    todoQueries: [], doneQueries: [], instancePosts: [], taskPosts: [],
    instances: INSTANCES_SEED.map((row) => ({ ...row })),
    tasks: TASKS_SEED.map((row) => ({ ...row })),
  };
  const INSTANCE_BASE = '/admin/v1/workflow/process-instances';
  const TASK_BASE = '/admin/v1/workflow/tasks';

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS_ALL, menus: [] });
    }
    if (p === '/admin/v1/workflow/process-definitions' && r.method === 'GET') {
      state.defQueries.push(r.url);
      return envelope({ records: DEFS, total: DEFS.length });
    }
    if (p === `${INSTANCE_BASE}/initiated` && r.method === 'GET') {
      state.initiatedQueries.push(r.url);
      const rows = state.instances.filter((row) => row.initiator === 'admin');
      return envelope({ records: rows, total: rows.length });
    }
    if (p.startsWith(INSTANCE_BASE) && r.method === 'GET') {
      const detailMatch = /^\/admin\/v1\/workflow\/process-instances\/([^/]+)\/history$/.exec(p);
      if (detailMatch) {
        state.historyQueries.push(r.url);
        // 9302 补 ENTER start-1 历史：reject 目标候选=实例历史 ENTER 节点（9402 所属实例）
        const history9302 = [
          { id: '9701', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T02:40:00Z', endAt: null, detail: null },
          { id: '9702', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-22T02:40:10Z', endAt: null, detail: null },
        ];
        return envelope(detailMatch[1] === '9301' ? HISTORY_9301
          : detailMatch[1] === '9302' ? history9302 : []);
      }
      const idMatch = /^\/admin\/v1\/workflow\/process-instances\/([^/]+)$/.exec(p);
      if (idMatch) {
        state.detailQueries.push(r.url);
        return envelope(state.instances.find((row) => row.id === idMatch[1]) ?? null);
      }
      state.instanceQueries.push(r.url);
      const definitionKey = r.url.searchParams.get('definitionKey');
      const status = r.url.searchParams.get('status');
      const businessKey = r.url.searchParams.get('businessKey');
      const rows = state.instances.filter((row) => {
        if (definitionKey && row.definitionKey !== definitionKey) return false;
        if (status && row.status !== status) return false;
        if (businessKey && row.businessKey !== businessKey) return false;
        return true;
      });
      return envelope({ records: rows, total: rows.length });
    }
    if (p.startsWith(INSTANCE_BASE) && r.method === 'POST') {
      const action = /^\/admin\/v1\/workflow\/process-instances\/([^/]+)\/(terminate|suspend|resume)$/.exec(p);
      if (action) {
        const id = action[1] as string;
        const op = action[2] as 'terminate' | 'suspend' | 'resume';
        const body = parseBody(r.body);
        state.instancePosts.push({ url: r.url, body });
        state.instances = state.instances.map((row) => {
          if (row.id !== id) return row;
          if (op === 'suspend') return { ...row, status: 'SUSPENDED', suspendReason: String(body.reason ?? '运维挂起') };
          if (op === 'resume') return { ...row, status: 'RUNNING', suspendReason: null };
          return { ...row, status: 'TERMINATED', endTime: '2026-11-22T10:00:00Z' };
        });
        return envelope(null);
      }
    }
    if ((p === `${TASK_BASE}/todo` || p === `${TASK_BASE}/done`) && r.method === 'GET') {
      const target = p.endsWith('/todo') ? state.todoQueries : state.doneQueries;
      target.push(r.url);
      const definitionKey = r.url.searchParams.get('definitionKey');
      const status = r.url.searchParams.get('status');
      const wanted = p.endsWith('/todo') ? ['CREATED', 'CLAIMED'] : ['COMPLETED', 'REJECTED'];
      const rows = state.tasks.filter((row) => {
        if (!wanted.includes(row.status)) return false;
        if (definitionKey && row.definitionKey !== definitionKey) return false;
        if (status && row.status !== status) return false;
        return true;
      });
      return envelope({ records: rows, total: rows.length });
    }
    if (p.startsWith(TASK_BASE) && r.method === 'POST') {
      const action = /^\/admin\/v1\/workflow\/tasks\/([^/]+)\/(complete|reject|claim|unclaim|transfer|delegate)$/.exec(p);
      if (action) {
        const id = action[1] as string;
        const op = action[2] as 'complete' | 'reject' | 'claim' | 'unclaim' | 'transfer' | 'delegate';
        const body = parseBody(r.body);
        state.taskPosts.push({ url: r.url, body });
        state.tasks = state.tasks.map((row) => {
          if (row.id !== id) return row;
          switch (op) {
            case 'claim': return { ...row, status: 'CLAIMED', assignee: 'admin', claimTime: '2026-11-22T10:00:00Z' };
            case 'unclaim': return { ...row, status: 'CREATED', assignee: null, claimTime: null };
            case 'complete': return { ...row, status: 'COMPLETED', completeTime: '2026-11-22T10:05:00Z' };
            case 'reject': return { ...row, status: 'REJECTED', completeTime: '2026-11-22T10:05:00Z' };
            case 'transfer': return { ...row, assignee: String(body.assignee ?? row.assignee) };
            case 'delegate': return { ...row, assignee: String(body.assignee ?? row.assignee) };
          }
        });
        return envelope(null);
      }
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

/** 打开 Tab（antd Tabs role=tab 节点；pitfall 9：以 role+name 定位）。 */
const openTab = async (name: string): Promise<void> => {
  fireEvent.click(screen.getByRole('tab', { name }));
};

/** 取激活 tabpane；若过滤区在折叠面板内则先展开（pitfall 11：FilterPanel 既有形态宽容适配）。 */
const activePane = async (): Promise<HTMLElement> => {
  const pane = await waitFor(() => {
    const el = document.querySelector('.ant-tabs-tabpane-active');
    expect(el, '应有激活的 Tab 面板').toBeTruthy();
    return el as HTMLElement;
  });
  const toggle = within(pane).queryByText(/展开进阶筛选/);
  if (toggle) fireEvent.click(toggle);
  return pane;
};

/** 行容器定位（antd Table 行 tr；businessKey 为行唯一锚点）。 */
const rowOf = (businessKey: string): HTMLElement => {
  const cell = screen.getByText(businessKey);
  const row = cell.closest('tr');
  expect(row, `businessKey ${businessKey} 应定位到表格行`).toBeTruthy();
  return row as HTMLElement;
};

/** Popconfirm 确认按钮点击（antd en/zh locale 双口径宽容：OK|确定）。 */
const confirmPopconfirm = async (titlePattern: RegExp): Promise<void> => {
  await waitFor(() => {
    expect(screen.getByText(titlePattern), 'Popconfirm 文案应出现').toBeTruthy();
  });
  const okButton = await waitFor(() => {
    const btn = screen.getAllByRole('button').find((el) => /^(OK|确\s*定)$/.test(el.textContent ?? ''));
    expect(btn, 'Popconfirm 确认按钮应出现').toBeTruthy();
    return btn as HTMLElement;
  });
  fireEvent.click(okButton);
};

/** 行内操作按钮（行作用域限定，避免多行同名按钮命中）。 */
const rowAction = (row: HTMLElement, name: RegExp): HTMLElement =>
  within(row).getByRole('button', { name });

/** Drawer 内容作用域（antd Drawer portal；表格行与抽屉摘要同文案时限定范围）。 */
const drawerScope = (): HTMLElement => {
  const el = document.querySelector('.ant-drawer-content');
  expect(el, 'Drawer 应已渲染').toBeTruthy();
  return el as HTMLElement;
};

/** FilterPanel 查询按钮（表单型筛选显式提交，JobsPage 口径；htmlType=submit 触发 onFinish）。 */
const submitFilters = async (pane: HTMLElement): Promise<void> => {
  fireEvent.click(within(pane).getByRole('button', { name: /查\s*询/ }));
};

/** 行状态徽标断言（重查行避免 refetch 后节点失效）。 */
const expectRowStatus = async (businessKey: string, statusText: string): Promise<void> => {
  await waitFor(() => {
    const row = rowOf(businessKey);
    expect(row.textContent, `${businessKey} 行应显示 ${statusText}`).toContain(statusText);
  }, { timeout: 5000 });
};

const renderWorkflowPage = async (permissions: string[] = PERMS_ALL): Promise<WorkflowApiState> => {
  await seedMe(permissions);
  const api = setupWorkflowApi();
  render(<AntApp><MemoryRouter><WorkflowPage /></MemoryRouter></AntApp>);
  await waitFor(() => {
    expect(screen.getByText('请假审批'), '定义 Tab 默认渲染且列表加载').toBeTruthy();
  });
  return api.state;
};

const openInstancesTab = async (state: WorkflowApiState): Promise<void> => {
  await openTab(TAB_INSTANCES);
  await waitFor(() => {
    expect(screen.getByText('EXP-20261122-001'), '实例列表应加载出行集').toBeTruthy();
    expect(state.instanceQueries.length, '实例分页查询应携带分页参数').toBeGreaterThanOrEqual(1);
  });
  const firstQuery = state.instanceQueries[0];
  expect(firstQuery?.searchParams.get('page')).toBe('1');
  expect(Number(firstQuery?.searchParams.get('size'))).toBeGreaterThan(0);
};

const renderTodoPage = async (permissions: string[] = PERMS_ALL): Promise<WorkflowApiState> => {
  await seedMe(permissions);
  const api = setupWorkflowApi();
  render(<AntApp><MemoryRouter><TodoCenterPage /></MemoryRouter></AntApp>);
  await waitFor(() => {
    expect(screen.getByText('EXP-20261122-001'), '我的待办列表应加载出行集').toBeTruthy();
    expect(api.state.todoQueries.length, 'todo 分页查询应发出').toBeGreaterThanOrEqual(1);
  });
  const firstQuery = api.state.todoQueries[0];
  expect(firstQuery?.searchParams.get('page')).toBe('1');
  expect(Number(firstQuery?.searchParams.get('size'))).toBeGreaterThan(0);
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

// ————— AC-1：三 Tab 化 + 定义 Tab 零回归 —————

describe('REQ20261122102730 /workflow 三 Tab 化（AC-1 + AC-12 定义面）', () => {
  it('should_render_three_tabs_with_definitions_intact_when_workflow_page_loaded', async () => {
    const state = await renderWorkflowPage();
    // 三 Tab 齐（定义/实例监控/待办中心入口提示）
    expect(screen.getByRole('tab', { name: TAB_DEFS })).toBeTruthy();
    expect(screen.getByRole('tab', { name: TAB_INSTANCES })).toBeTruthy();
    expect(screen.getByRole('tab', { name: TAB_DEFS }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: TAB_TODOS })).toBeTruthy();
    // 定义 Tab 零回归：三行定义列照旧（BPM-2 既有形态）
    expect(screen.getByText('报销申请')).toBeTruthy();
    expect(screen.getByText('资产报废')).toBeTruthy();
    // Tab 化不误发实例/任务查询（默认面仍只查定义）
    expect(state.instanceQueries).toHaveLength(0);
    expect(state.todoQueries).toHaveLength(0);
  });

  it('should_show_todo_entry_hint_when_todos_tab_clicked', async () => {
    await renderWorkflowPage();
    await openTab(TAB_TODOS);
    // 第三 Tab 仅承载独立页入口提示（确认③：待办为独立路由页，不入三 Tab）
    await waitFor(() => {
      expect(screen.getByText(TODO_GO)).toBeTruthy();
      expect(screen.getByText(/独立页面/)).toBeTruthy();
    });
    expect(screen.queryByText('EXP-20261122-001'), '入口提示页不应内联渲染待办列表').toBeNull();
  });

  it('should_keep_definitions_rows_when_switched_back_from_instances_tab', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    await openTab(TAB_DEFS);
    // 定义 Tab 零回归：切回重挂载后重新取数（destroyOnHidden 口径），断言待行集渲染
    await waitFor(() => {
      expect(screen.getByText('请假审批'), '切回定义 Tab 后行集应重新加载').toBeTruthy();
      expect(screen.getByText('报销申请')).toBeTruthy();
    });
    expect(state.defQueries.length).toBeGreaterThanOrEqual(1);
  });
});

// ————— AC-2：实例监控过滤 + 分页 + 权限分控 —————

describe('REQ20261122102730 实例监控 Tab（AC-2）', () => {
  it('should_render_instance_list_with_status_badges_when_tab_opened', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    expect(screen.getByText('EXP-20261122-002')).toBeTruthy();
    expect(screen.getByText('EXP-20261120-009')).toBeTruthy();
    // 状态徽标（状态机四态文案面）
    expect(screen.getAllByText(STATUS_RUNNING).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText(STATUS_SUSPENDED).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(STATUS_COMPLETED).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(STATUS_TERMINATED).length).toBeGreaterThanOrEqual(1);
  });

  it('should_filter_instances_by_status_when_status_option_picked', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    // 过滤下拉打结构节点（pitfall 7）：激活 tabpane 容器内 .ant-select-selector 展开
    const pane = await activePane();
    const selectors = pane.querySelectorAll('.ant-select-selector');
    expect(selectors.length, '实例 Tab 过滤区应有状态下拉').toBeGreaterThanOrEqual(1);
    fireEvent.mouseDown(selectors[selectors.length - 1] as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '状态下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    // 浮层容器内按文案选中（pitfall 8）→ 点「查询」显式提交（FilterPanel 表单型口径）
    fireEvent.click(within(dropdown).getByText(STATUS_RUNNING));
    await submitFilters(pane);
    await waitFor(() => {
      const last = state.instanceQueries[state.instanceQueries.length - 1];
      expect(last.searchParams.get('status')).toBe('RUNNING');
    });
  });

  it('should_filter_instances_by_definition_key_when_input_submitted', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    // 过滤标签（i18n instance.filters 锚点）：流程标识/业务键输入面存在
    const pane = await activePane();
    const keyInput = within(pane).getByLabelText('流程标识') as HTMLInputElement;
    expect(keyInput).toBeTruthy();
    expect(within(pane).getByLabelText('业务键')).toBeTruthy();
    fireEvent.change(keyInput, { target: { value: 'expense-claim' } });
    await submitFilters(pane);
    await waitFor(() => {
      const last = state.instanceQueries[state.instanceQueries.length - 1];
      expect(last.searchParams.get('definitionKey')).toBe('expense-claim');
    });
  });

  it('should_hide_instance_row_actions_when_user_lacks_manage_permission', async () => {
    // 分控 fail-closed：无 80 → 行操作（挂起/终止/恢复）不可见（读面列表照常）
    const state = await renderWorkflowPage([LIST_PERM, DEPLOY_PERM]);
    await openInstancesTab(state);
    expect(screen.queryAllByRole('button', { name: ACTION_SUSPEND })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: ACTION_TERMINATE })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: ACTION_RESUME })).toHaveLength(0);
    expect(state.instanceQueries.length, '读面不受写分控影响').toBeGreaterThanOrEqual(1);
  });

  it('should_show_instance_row_actions_when_user_has_manage_permission', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    // RUNNING 行 → 挂起/终止；SUSPENDED 行 → 恢复；终态行无行操作（确认⑦按状态分控）
    const runningRow = rowOf('EXP-20261122-001');
    expect(rowAction(runningRow, ACTION_SUSPEND)).toBeTruthy();
    expect(rowAction(runningRow, ACTION_TERMINATE)).toBeTruthy();
    const suspendedRow = rowOf('EXP-20261122-003');
    expect(rowAction(suspendedRow, ACTION_RESUME)).toBeTruthy();
    expect(within(suspendedRow).queryByRole('button', { name: ACTION_SUSPEND })).toBeNull();
    const doneRow = rowOf('EXP-20261121-001');
    expect(within(doneRow).queryByRole('button', { name: ACTION_SUSPEND })).toBeNull();
    expect(within(doneRow).queryByRole('button', { name: ACTION_RESUME })).toBeNull();
  });
});

// ————— AC-4：行操作 Popconfirm → 端点调用 + 状态迁移 —————

describe('REQ20261122102730 实例行操作（AC-4）', () => {
  it('should_suspend_running_instance_when_popconfirm_confirmed', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    fireEvent.click(rowAction(rowOf('EXP-20261122-001'), ACTION_SUSPEND));
    await confirmPopconfirm(/确认挂起该流程实例/);
    await waitFor(() => {
      expect(state.instancePosts.length, '挂起端点应被调用').toBe(1);
    });
    expect(state.instancePosts[0]?.url.pathname).toBe('/admin/v1/workflow/process-instances/9301/suspend');
    await expectRowStatus('EXP-20261122-001', STATUS_SUSPENDED);
  });

  it('should_terminate_running_instance_when_popconfirm_confirmed', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    fireEvent.click(rowAction(rowOf('EXP-20261122-001'), ACTION_TERMINATE));
    await confirmPopconfirm(/确认终止该流程实例/);
    await waitFor(() => {
      expect(state.instancePosts.length, '终止端点应被调用').toBe(1);
    });
    expect(state.instancePosts[0]?.url.pathname).toBe('/admin/v1/workflow/process-instances/9301/terminate');
    await expectRowStatus('EXP-20261122-001', STATUS_TERMINATED);
  });

  it('should_resume_suspended_instance_when_popconfirm_confirmed', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    fireEvent.click(rowAction(rowOf('EXP-20261122-003'), ACTION_RESUME));
    await confirmPopconfirm(/确认恢复该流程实例/);
    await waitFor(() => {
      expect(state.instancePosts.length, '恢复端点应被调用').toBe(1);
    });
    expect(state.instancePosts[0]?.url.pathname).toBe('/admin/v1/workflow/process-instances/9303/resume');
    await expectRowStatus('EXP-20261122-003', STATUS_RUNNING);
  });
});

// ————— AC-3：实例详情 Drawer 五块信息架构 —————

describe('REQ20261122102730 实例详情 Drawer（AC-3）', () => {
  it('should_assemble_detail_drawer_five_blocks_when_detail_opened', async () => {
    const state = await renderWorkflowPage();
    await openInstancesTab(state);
    fireEvent.click(rowAction(rowOf('EXP-20261122-001'), ACTION_DETAIL));
    // Drawer 打开 → 详情 + 历史两条 GET（readOnly 流程图渲染面=X6 浏览器实测项）
    await waitFor(() => {
      expect(screen.getByText(DRAWER_INSTANCE), '实例详情 Drawer 应打开').toBeTruthy();
      expect(state.detailQueries.length).toBeGreaterThanOrEqual(1);
      expect(state.historyQueries.length).toBeGreaterThanOrEqual(1);
    });
    // ① 摘要（状态/发起人/业务键；suspendReason 仅挂起态展示；业务键在表格行与抽屉摘要双命中）
    expect(screen.getByText('摘要')).toBeTruthy();
    await waitFor(() => {
      expect(screen.getAllByText(STATUS_RUNNING).length).toBeGreaterThanOrEqual(1);
    });
    expect(screen.getAllByText('EXP-20261122-001').length).toBeGreaterThanOrEqual(1);
    // ② 活跃节点 + 活动任务
    expect(screen.getByText('活跃节点')).toBeTruthy();
    expect(screen.getByText('活动任务')).toBeTruthy();
    await waitFor(() => {
      expect(screen.getAllByText('主管审批').length, '活跃节点/活动任务应装配').toBeGreaterThanOrEqual(1);
    });
    // ③ 变量摘要
    expect(screen.getByText('变量摘要')).toBeTruthy();
    // ④ 历史时间线（action 分色=label 面：进入/离开/驳回回流；REJECT_EDGE detail 随条目回显）
    expect(screen.getByText('历史时间线')).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText('驳回回流'), 'REJECT_EDGE action 应分色渲染').toBeTruthy();
      expect(screen.getByText('驳回：金额超限回流')).toBeTruthy();
      expect(screen.getAllByText('进入').length).toBeGreaterThanOrEqual(1);
    });
    // ⑤ 流程图（readOnly 渲染块装配面；activeNodes 高亮/边标红=X6 浏览器实测项）
    expect(screen.getByText('流程图')).toBeTruthy();
  });
});

// ————— AC-5：待办中心两子区 + 投影列 —————

describe('REQ20261122102730 待办中心页（AC-5）', () => {
  it('should_render_two_segments_with_projection_columns_when_todo_page_loaded', async () => {
    const state = await renderTodoPage();
    // 两子区段（Segmented 标签+页面标题可能双命中，宽容多节点口径）
    expect(screen.getAllByText(SEG_TODO).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(SEG_INITIATED).length).toBeGreaterThanOrEqual(1);
    // 我的待办行集：CREATED/CLAIMED 两行 + 投影列（definitionName/businessKey/initiator 契约⑤禁 N+1）
    expect(screen.getAllByText('EXP-20261122-001').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('EXP-20261122-002').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('报销申请').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('待认领').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('已认领').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('admin').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('ops').length).toBeGreaterThanOrEqual(1);
    expect(state.initiatedQueries, '默认子区不应误发我发起查询').toHaveLength(0);
  });

  it('should_load_initiated_instances_when_initiated_segment_selected', async () => {
    const state = await renderTodoPage();
    fireEvent.click(screen.getAllByText(SEG_INITIATED)[0] as HTMLElement);
    await waitFor(() => {
      expect(state.initiatedQueries.length, '我发起子区应发 initiated 查询').toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('EXP-20261121-001').length, '我发起行集应加载').toBeGreaterThanOrEqual(1);
    });
    const firstQuery = state.initiatedQueries[0];
    expect(firstQuery?.searchParams.get('page')).toBe('1');
    // 我发起=initiator 本人：ops 的实例不混入
    expect(screen.queryAllByText('EXP-20261122-002')).toHaveLength(0);
  });
});

// ————— AC-6/7/8：任务操作面（认领/办理/驳回/转办/委托） —————

describe('REQ20261122102730 任务操作（AC-6/7/8）', () => {
  it('should_claim_created_task_and_switch_actions_when_popconfirm_confirmed', async () => {
    const state = await renderTodoPage();
    // CREATED 候选任务 → 认领（Popconfirm）
    fireEvent.click(rowAction(rowOf('EXP-20261122-001'), ACTION_CLAIM));
    await confirmPopconfirm(/确认认领该任务/);
    await waitFor(() => {
      const claimPost = state.taskPosts.find((p) => p.url.pathname.endsWith('/9401/claim'));
      expect(claimPost, '认领端点应被调用').toBeTruthy();
    });
    // CLAIMED + 操作面切换（取消认领/办理可见）
    await expectRowStatus('EXP-20261122-001', TASK_STATUS_CLAIMED);
    await waitFor(() => {
      const row = rowOf('EXP-20261122-001');
      expect(within(row).getByRole('button', { name: ACTION_UNCLAIM })).toBeTruthy();
      expect(within(row).getByRole('button', { name: ACTION_COMPLETE })).toBeTruthy();
    });
  });

  it('should_hide_task_operate_actions_when_user_lacks_operate_permission', async () => {
    // 写面分控(82) fail-closed：无 task:operate → 认领/办理/转办/委托不可见（列表照常）
    const state = await renderTodoPage([LIST_PERM, DEPLOY_PERM, INSTANCE_PERM, TASK_LIST_PERM]);
    expect(screen.getAllByText('待认领').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryAllByRole('button', { name: ACTION_CLAIM })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: ACTION_COMPLETE })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: ACTION_TRANSFER })).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: ACTION_DELEGATE })).toHaveLength(0);
    expect(state.todoQueries.length).toBeGreaterThanOrEqual(1);
  });

  it('should_complete_task_with_form_when_drawer_submitted', async () => {
    const state = await renderTodoPage();
    // CLAIMED 任务 → 办理 Drawer → 表单提交 POST complete → 待办消失
    fireEvent.click(rowAction(rowOf('EXP-20261122-002'), ACTION_COMPLETE));
    await waitFor(() => {
      expect(screen.getByText(DRAWER_TASK), '任务详情 Drawer 应打开').toBeTruthy();
    });
    const commentInput = await waitFor(() => {
      const el = screen.getAllByPlaceholderText(COMMENT_PLACEHOLDER)[0];
      expect(el, '办理意见输入应出现').toBeTruthy();
      return el as HTMLInputElement;
    });
    fireEvent.change(commentInput, { target: { value: '同意报销' } });
    fireEvent.click(within(drawerScope()).getByRole('button', { name: SUBMIT_COMPLETE }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/9402/complete'));
      expect(post, 'complete 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ comment: '同意报销' });
    });
    // 已办结任务从待办消失（todo 行集刷新）
    await waitFor(() => {
      expect(screen.queryAllByText('EXP-20261122-002'), '已办结任务应从待办消失').toHaveLength(0);
    });
  });

  it('should_reject_task_with_target_node_selection_when_reject_submitted', async () => {
    const state = await renderTodoPage();
    fireEvent.click(rowAction(rowOf('EXP-20261122-002'), ACTION_COMPLETE));
    // 驳回面内联于任务 Drawer（驳回区标题+确认按钮常驻装配，无独立入口——/驳\s*回/ 同时命中
    // 区标题与「确认驳回」按钮，故以 getAllByText 宽容断言）
    const drawer = await waitFor(() => {
      const el = drawerScope();
      expect(within(el).getAllByText(/驳\s*回/).length, '驳回区（标题+确认按钮）应装配')
        .toBeGreaterThanOrEqual(1);
      expect(within(el).getByRole('button', { name: SUBMIT_REJECT }), '确认驳回按钮应可见').toBeTruthy();
      return el;
    });
    // targetNodeId 选择（候选=实例历史 ENTER 节点；9302 历史 ENTER start-1）：
    // Drawer 内唯一 Select=驳回目标（complete 区仅 textarea），容器内 .ant-select-selector 展开
    const selectors = drawer.querySelectorAll('.ant-select-selector');
    expect(selectors.length, '驳回目标节点 Select 应装配').toBeGreaterThanOrEqual(1);
    fireEvent.mouseDown(selectors[0] as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '驳回目标下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('start-1'));
    // 确认驳回 → POST reject（targetNodeId 同形透传；空 comment 随信封 null）
    fireEvent.click(within(drawer).getByRole('button', { name: SUBMIT_REJECT }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/9402/reject'));
      expect(post, 'reject 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ targetNodeId: 'start-1', comment: null });
    });
    // REJECTED 行退出待办行集
    await waitFor(() => {
      expect(screen.queryAllByText('EXP-20261122-002')).toHaveLength(0);
    });
  });

  it('should_transfer_task_when_assignee_submitted', async () => {
    const state = await renderTodoPage();
    fireEvent.click(rowAction(rowOf('EXP-20261122-002'), ACTION_TRANSFER));
    await waitFor(() => {
      expect(screen.getByText(MODAL_TRANSFER), '转办弹层应打开').toBeTruthy();
    });
    const assigneeInput = await waitFor(() => {
      const el = screen.getAllByPlaceholderText(ASSIGNEE_PLACEHOLDER)[0];
      expect(el, '转办目标人输入应出现').toBeTruthy();
      return el as HTMLInputElement;
    });
    fireEvent.change(assigneeInput, { target: { value: 'ops2' } });
    fireEvent.click(screen.getAllByText(SUBMIT_ASSIGNEE)[0] as HTMLElement);
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/9402/transfer'));
      expect(post, 'transfer 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ assignee: 'ops2' });
    });
  });

  it('should_delegate_task_when_assignee_submitted', async () => {
    const state = await renderTodoPage();
    fireEvent.click(rowAction(rowOf('EXP-20261122-002'), ACTION_DELEGATE));
    await waitFor(() => {
      expect(screen.getByText(MODAL_DELEGATE), '委托弹层应打开').toBeTruthy();
    });
    const assigneeInput = await waitFor(() => {
      const el = screen.getAllByPlaceholderText(ASSIGNEE_PLACEHOLDER)[0];
      expect(el, '委托目标人输入应出现').toBeTruthy();
      return el as HTMLInputElement;
    });
    fireEvent.change(assigneeInput, { target: { value: 'ops3' } });
    fireEvent.click(screen.getAllByText(SUBMIT_ASSIGNEE)[0] as HTMLElement);
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/9402/delegate'));
      expect(post, 'delegate 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ assignee: 'ops3' });
    });
  });
});

// i18n 触达（避免 tree-shake 语义盲区；文案锚点=zh-CN workflow 资源包）
void i18n;
