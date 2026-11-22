// @vitest-environment jsdom
/**
 * REQ-20261122-150750 BPM-4——审批详情深化+任务办理重构（页面装配面）。
 *
 * 契约口径（contracts/REQ-20261122-150750.md 确认②③⑤⑥⑨ + AC-1~6）：
 *   - AC-1：待办行「办理」→ 审批详情 Drawer 五块（任务信息/审批操作区/实例上下文/历史时间线/流程图）；
 *   - AC-2：动态表单 Form.List 键值对提交 {form:{k:v},comment}；空表单仅 comment 合法；
 *   - AC-3：驳回候选=ENTER 且 detail.delegate 为空+nodeId 去重+含 StartEvent（rejectCandidatesOf 单元+UI 面）；
 *   - AC-4：Drawer 操作区按状态分控（CREATED 仅 claim；CLAIMED 显 unclaim/complete/reject/transfer/delegate）
 *     + 82 权限分控（fail-closed）；
 *   - AC-5：done 行「查看」→ 只读详情（无操作按钮）+ 历史/流程图可用；
 *   - AC-6：我发起行「查看」→ 实例详情视角（复用 readonly 三件套）。
 *
 * 装配照 web/tests/REQ20261122102730-workflowPages.test.tsx 先例：fetch stubGlobal 精确 pathname
 * 路由 + useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + MemoryRouter。
 * canvasFactory 必须桩替换（BPM-3 实测坑：jsdom 收集期 X6 polyfill 抛 Element is not defined）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import { i18n, useMeStore, type HistoryActivityView } from '@acme/core';
import { TodoCenterPage } from '../src/pages/TodoCenterPage';
import { rejectCandidatesOf } from '../src/pages/workflow/ApprovalDetailDrawer';

vi.setConfig({ testTimeout: 45000 });

// X6 链路隔离（BPM-3 坑）：画布装配桩替换，renderReadOnlyBpmn 可 spy 校验 activeNodeIds 透传
const renderReadOnlyBpmnMock = vi.fn();
vi.mock('../src/pages/workflow/canvasFactory', () => ({
  createBpmnCanvas: vi.fn(() => ({ dispose: vi.fn() })),
  renderReadOnlyBpmn: (...args: unknown[]) => renderReadOnlyBpmnMock(...args),
  applyCanvasTheme: vi.fn(),
}));

const LIST_PERM = 'workflow:definition:list';
const TASK_LIST_PERM = 'workflow:task:list';
const TASK_OPERATE_PERM = 'workflow:task:operate';
const PERMS_TODO = [LIST_PERM, TASK_LIST_PERM, TASK_OPERATE_PERM];
const PERMS_READONLY = [LIST_PERM, TASK_LIST_PERM];

// 文案锚点（zh-CN workflow 资源包）
const SEG_DONE = '已办';
const SEG_INITIATED = '我发起';
const ACTION_COMPLETE = /^办\s*理$/;
const ACTION_VIEW = /^查\s*看$/;
const DRAWER_TITLE = '审批详情';
const BLOCK_TASK = '任务信息';
const BLOCK_APPROVAL = '审批操作区';
const BLOCK_CONTEXT = '实例上下文';
const BLOCK_HISTORY = '历史时间线';
const BLOCK_DIAGRAM = '流程图';
const BTN_CLAIM = /^认\s*领$/;
const BTN_UNCLAIM = /^取消认领$/;
const BTN_TRANSFER = /^转\s*办$/;
const BTN_DELEGATE = /^委\s*托$/;
const SUBMIT_COMPLETE = /提\s*交\s*办\s*结/;
const SUBMIT_REJECT = /确\s*认\s*驳\s*回/;
const ADD_FORM_ITEM = '添加表单字段';
const REMOVE_FORM_ITEM = '移除';
const FORM_KEY_PLACEHOLDER = '如 days';
const FORM_VALUE_PLACEHOLDER = '如 3';
const COMMENT_PLACEHOLDER = '填写办理意见（可选）';
const READONLY_HINT = '该任务已办结';
const DRAWER_INSTANCE = '实例详情';

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

// ————— fixtures（委托污染样例 8806：delegate ENTER 记录 + StartEvent 历史） —————

interface TaskRow {
  id: string; instanceId: string; nodeId: string; nodeName: string | null; assignee: string | null;
  status: 'CREATED' | 'CLAIMED' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';
  claimTime: string | null; completeTime: string | null; createTime: string;
  definitionKey: string | null; definitionName: string | null; businessKey: string | null; initiator: string | null;
}

const baseTask = (over: Partial<TaskRow> & { id: string; instanceId: string; businessKey: string; status: TaskRow['status'] }): TaskRow => ({
  nodeId: 'task-1', nodeName: '主管审批', assignee: null, claimTime: null, completeTime: null,
  createTime: '2026-11-22T04:10:00Z', definitionKey: 'expense-claim', definitionName: '报销申请',
  initiator: 'ops', ...over,
});

const TASKS_SEED: TaskRow[] = [
  baseTask({ id: '8801', instanceId: '8806', businessKey: 'AP-20261122-001', status: 'CLAIMED', assignee: 'admin', claimTime: '2026-11-22T04:20:00Z' }),
  baseTask({ id: '8802', instanceId: '8807', businessKey: 'AP-20261122-002', status: 'CREATED' }),
  baseTask({ id: '8803', instanceId: '8808', businessKey: 'AP-20261121-003', status: 'COMPLETED', assignee: 'admin', claimTime: '2026-11-21T10:00:00Z', completeTime: '2026-11-21T11:00:00Z', createTime: '2026-11-21T09:30:00Z', initiator: 'admin' }),
  baseTask({ id: '8804', instanceId: '8806', businessKey: 'AP-20261122-004', status: 'CLAIMED', assignee: 'admin', claimTime: '2026-11-22T05:00:00Z' }),
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
  suspendReason: null, startTime: '2026-11-22T04:10:00Z', endTime: null,
  variables: { amount: 660 }, activeNodes: [], tasks: [], ...over,
});

const INSTANCES_SEED: InstanceRow[] = [
  baseInstance({
    id: '8806', businessKey: 'AP-20261122-001', initiator: 'ops', status: 'RUNNING',
    activeNodes: [{ nodeId: 'task-1', nodeName: '主管审批', nodeType: 'UserTask', enterAt: '2026-11-22T04:10:05Z' }],
    tasks: [TASKS_SEED[0] as unknown as InstanceRow['tasks'][number]],
  }),
  baseInstance({ id: '8807', businessKey: 'AP-20261122-002', initiator: 'ops', status: 'RUNNING' }),
  baseInstance({
    id: '8808', businessKey: 'AP-20261121-003', initiator: 'admin', status: 'COMPLETED',
    startTime: '2026-11-21T09:30:00Z', endTime: '2026-11-21T11:05:00Z',
  }),
];

const HIST_8806: HistoryActivityView[] = [
  { id: '9701', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T04:10:00Z', endAt: null, detail: null },
  { id: '9702', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-22T04:10:02Z', endAt: '2026-11-22T04:10:05Z', detail: null },
  // delegate 污染记录（detail.delegate=委托 owner 落库形态）：驳回候选须剔除
  { id: '9703', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'boss', startAt: '2026-11-22T04:10:05Z', endAt: '2026-11-22T04:18:00Z', detail: '{"delegate":"boss","comment":"出差委托"}' },
  { id: '9704', nodeId: 'task-1', nodeType: 'UserTask', action: 'LEAVE', assignee: 'boss', startAt: '2026-11-22T04:18:00Z', endAt: '2026-11-22T04:18:30Z', detail: null },
  { id: '9705', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-22T04:18:30Z', endAt: null, detail: null },
];

const HIST_8808: HistoryActivityView[] = [
  { id: '9711', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-21T09:30:00Z', endAt: null, detail: null },
  { id: '9712', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-21T09:30:02Z', endAt: '2026-11-21T09:30:05Z', detail: null },
  { id: '9713', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-21T09:30:05Z', endAt: null, detail: null },
];

const SAMPLE_XML = '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"><bpmn:process id="p1"/></bpmn:definitions>';

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

interface ApiState {
  todoQueries: URL[];
  doneQueries: URL[];
  initiatedQueries: URL[];
  detailQueries: URL[];
  historyQueries: URL[];
  taskPosts: { url: URL; body: Record<string, unknown> }[];
  tasks: TaskRow[];
  instances: InstanceRow[];
}

/** 待办中心域 fetch 桩：任务 todo/done + 实例详情/历史 + 定义详情（XML）+ 任务动作。 */
const setupTodoApi = (permissions: string[]) => {
  const state: ApiState = {
    todoQueries: [], doneQueries: [], initiatedQueries: [], detailQueries: [], historyQueries: [],
    taskPosts: [], tasks: TASKS_SEED.map((row) => ({ ...row })), instances: INSTANCES_SEED.map((row) => ({ ...row })),
  };
  const INSTANCE_BASE = '/admin/v1/workflow/process-instances';
  const TASK_BASE = '/admin/v1/workflow/tasks';

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions, menus: [] });
    }
    if (p === '/admin/v1/workflow/process-definitions' && r.method === 'GET') {
      return envelope({ records: [], total: 0 });
    }
    if (/^\/admin\/v1\/workflow\/process-definitions\/[^/]+$/.test(p) && r.method === 'GET') {
      return envelope({ id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED', bpmnXml: SAMPLE_XML, layoutJson: null, createdAt: '', deployedAt: '' });
    }
    if (p === `${INSTANCE_BASE}/initiated` && r.method === 'GET') {
      state.initiatedQueries.push(r.url);
      const rows = state.instances.filter((row) => row.initiator === 'admin');
      return envelope({ records: rows, total: rows.length });
    }
    if (p.startsWith(INSTANCE_BASE) && r.method === 'GET') {
      const historyMatch = /^\/admin\/v1\/workflow\/process-instances\/([^/]+)\/history$/.exec(p);
      if (historyMatch) {
        state.historyQueries.push(r.url);
        return envelope(historyMatch[1] === '8806' ? HIST_8806 : historyMatch[1] === '8808' ? HIST_8808 : []);
      }
      const idMatch = /^\/admin\/v1\/workflow\/process-instances\/([^/]+)$/.exec(p);
      if (idMatch) {
        state.detailQueries.push(r.url);
        return envelope(state.instances.find((row) => row.id === idMatch[1]) ?? null);
      }
    }
    if ((p === `${TASK_BASE}/todo` || p === `${TASK_BASE}/done`) && r.method === 'GET') {
      const target = p.endsWith('/todo') ? state.todoQueries : state.doneQueries;
      target.push(r.url);
      const wanted = p.endsWith('/todo') ? ['CREATED', 'CLAIMED'] : ['COMPLETED', 'REJECTED'];
      const rows = state.tasks.filter((row) => wanted.includes(row.status));
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

const rowOf = (businessKey: string): HTMLElement => {
  const cell = screen.getByText(businessKey);
  const row = cell.closest('tr');
  expect(row, `businessKey ${businessKey} 应定位到表格行`).toBeTruthy();
  return row as HTMLElement;
};

const drawerScope = (): HTMLElement => {
  const el = document.querySelector('.ant-drawer-content');
  expect(el, 'Drawer 应已渲染').toBeTruthy();
  return el as HTMLElement;
};

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

const renderTodoPage = async (permissions: string[] = PERMS_TODO): Promise<{ state: ApiState; fetchMock: ReturnType<typeof vi.fn> }> => {
  await seedMe(permissions);
  const api = setupTodoApi(permissions);
  render(<AntApp><MemoryRouter><TodoCenterPage /></MemoryRouter></AntApp>);
  await waitFor(() => {
    expect(screen.getByText('AP-20261122-001'), '待办列表应加载出行集').toBeTruthy();
    expect(api.state.todoQueries.length, 'todo 分页查询应发出').toBeGreaterThanOrEqual(1);
  });
  return api;
};

/** 打开任务审批详情 Drawer（待办行「办理」入口）。 */
const openApprovalDrawer = async (businessKey: string): Promise<HTMLElement> => {
  fireEvent.click(within(rowOf(businessKey)).getByRole('button', { name: ACTION_COMPLETE }));
  const drawer = await waitFor(() => {
    const el = drawerScope();
    expect(within(el).getByText(DRAWER_TITLE), '审批详情 Drawer 应打开').toBeTruthy();
    return el;
  });
  return drawer;
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

// ————— AC-1：审批详情 Drawer 五块装配 —————

describe('REQ20261122150750 审批详情五块装配（AC-1）', () => {
  it('should_assemble_five_blocks_when_todo_row_handle_clicked', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-001');
    // 五块标题齐
    expect(within(drawer).getByText(BLOCK_TASK)).toBeTruthy();
    expect(within(drawer).getByText(BLOCK_APPROVAL)).toBeTruthy();
    await waitFor(() => {
      expect(within(drawer).getByText(BLOCK_CONTEXT)).toBeTruthy();
      expect(within(drawer).getByText(BLOCK_HISTORY)).toBeTruthy();
      expect(within(drawer).getByText(BLOCK_DIAGRAM)).toBeTruthy();
    });
    // 实例上下文摘要（definitionName/businessKey/initiator/startTime 取自实例详情）
    await waitFor(() => {
      expect(state.detailQueries.length).toBeGreaterThanOrEqual(1);
      expect(state.historyQueries.length).toBeGreaterThanOrEqual(1);
      expect(within(drawer).getByText('发起时间')).toBeTruthy();
    });
    // 历史时间线 action 分色 label 面（进入/离开随 HIST_8806 回显）
    await waitFor(() => {
      expect(within(drawer).getAllByText('进入').length).toBeGreaterThanOrEqual(1);
      expect(within(drawer).getAllByText('离开').length).toBeGreaterThanOrEqual(1);
    });
    // readOnly 流程图装配：renderReadOnlyBpmn 收到 activeNodeIds=活跃节点+本任务 nodeId
    await waitFor(() => {
      expect(renderReadOnlyBpmnMock.mock.calls.length).toBeGreaterThanOrEqual(1);
      const payload = renderReadOnlyBpmnMock.mock.calls[renderReadOnlyBpmnMock.mock.calls.length - 1]?.[1] as { activeNodeIds: Set<string>; rejectEdgeIds: Set<string> };
      expect(payload.activeNodeIds.has('task-1')).toBe(true);
    });
  });
  it('should_simplify_row_entries_when_todo_and_done_lists_rendered', async () => {
    const { state } = await renderTodoPage();
    // 列表精简：行内 claim/unclaim/转办/委托按钮收编进 Drawer，仅留「办理」单入口（CREATED 与 CLAIMED 行同口径）
    expect(within(rowOf('AP-20261122-001')).getByRole('button', { name: ACTION_COMPLETE })).toBeTruthy();
    expect(within(rowOf('AP-20261122-001')).queryByRole('button', { name: BTN_UNCLAIM })).toBeNull();
    expect(within(rowOf('AP-20261122-001')).queryByRole('button', { name: BTN_TRANSFER })).toBeNull();
    expect(within(rowOf('AP-20261122-001')).queryByRole('button', { name: BTN_DELEGATE })).toBeNull();
    expect(within(rowOf('AP-20261122-002')).getByRole('button', { name: ACTION_COMPLETE })).toBeTruthy();
    expect(within(rowOf('AP-20261122-002')).queryByRole('button', { name: BTN_CLAIM })).toBeNull();
    // done 行仅「查看」入口，无「办理」/认领残留
    fireEvent.click(screen.getAllByText(SEG_DONE)[0] as HTMLElement);
    await waitFor(() => {
      expect(state.doneQueries.length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('AP-20261121-003')).toBeTruthy();
    });
    expect(within(rowOf('AP-20261121-003')).getByRole('button', { name: ACTION_VIEW })).toBeTruthy();
    expect(within(rowOf('AP-20261121-003')).queryByRole('button', { name: ACTION_COMPLETE })).toBeNull();
    expect(within(rowOf('AP-20261121-003')).queryByRole('button', { name: BTN_CLAIM })).toBeNull();
  });
});

// ————— AC-2：动态表单提交载荷 —————

describe('REQ20261122150750 动态表单载荷（AC-2）', () => {
  it('should_post_form_kv_and_comment_when_dynamic_form_filled', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-001');
    // 增一行键值对（Form.List 可增删行）
    fireEvent.click(within(drawer).getByRole('button', { name: ADD_FORM_ITEM }));
    const keyInput = await waitFor(() => {
      const el = within(drawer).getAllByPlaceholderText(FORM_KEY_PLACEHOLDER)[0];
      expect(el, '键名输入应出现').toBeTruthy();
      return el as HTMLInputElement;
    });
    fireEvent.change(keyInput, { target: { value: 'amount' } });
    fireEvent.change(within(drawer).getAllByPlaceholderText(FORM_VALUE_PLACEHOLDER)[0] as HTMLInputElement, { target: { value: '1280' } });
    fireEvent.change(within(drawer).getAllByPlaceholderText(COMMENT_PLACEHOLDER)[0] as HTMLInputElement, { target: { value: '同意' } });
    fireEvent.click(within(drawer).getByRole('button', { name: SUBMIT_COMPLETE }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/8801/complete'));
      expect(post, 'complete 端点应被调用').toBeTruthy();
      // 契约⑦形状修正：顶层 {form:{k:v}, comment}（旧形状 comment 被后端丢弃的缺口已修）
      expect(post?.body).toEqual({ form: { amount: '1280' }, comment: '同意' });
    });
  });

  it('should_post_comment_only_when_form_left_empty', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-004');
    fireEvent.change(within(drawer).getAllByPlaceholderText(COMMENT_PLACEHOLDER)[0] as HTMLInputElement, { target: { value: '仅意见' } });
    fireEvent.click(within(drawer).getByRole('button', { name: SUBMIT_COMPLETE }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/8804/complete'));
      expect(post, 'complete 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ comment: '仅意见' });
    });
  });

  it('should_keep_only_remaining_keys_when_form_row_removed', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-001');
    // 增两行键值对（Form.List 可增删行）
    fireEvent.click(within(drawer).getByRole('button', { name: ADD_FORM_ITEM }));
    fireEvent.click(within(drawer).getByRole('button', { name: ADD_FORM_ITEM }));
    const keyInputs = await waitFor(() => {
      const els = within(drawer).getAllByPlaceholderText(FORM_KEY_PLACEHOLDER);
      expect(els.length, '两行键名输入应出现').toBe(2);
      return els as HTMLInputElement[];
    });
    fireEvent.change(keyInputs[0], { target: { value: 'amount' } });
    fireEvent.change(within(drawer).getAllByPlaceholderText(FORM_VALUE_PLACEHOLDER)[0] as HTMLInputElement, { target: { value: '1280' } });
    fireEvent.change(keyInputs[1], { target: { value: 'reason' } });
    fireEvent.change(within(drawer).getAllByPlaceholderText(FORM_VALUE_PLACEHOLDER)[1] as HTMLInputElement, { target: { value: '出差' } });
    // 删第二行后仅剩首行键入 form
    const removeButtons = within(drawer).getAllByRole('button', { name: REMOVE_FORM_ITEM });
    expect(removeButtons.length, '每行应配移除按钮').toBe(2);
    fireEvent.click(removeButtons[1]);
    fireEvent.change(within(drawer).getAllByPlaceholderText(COMMENT_PLACEHOLDER)[0] as HTMLInputElement, { target: { value: '部分同意' } });
    fireEvent.click(within(drawer).getByRole('button', { name: SUBMIT_COMPLETE }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/8801/complete'));
      expect(post, 'complete 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ form: { amount: '1280' }, comment: '部分同意' });
    });
  });
});

// ————— AC-3：驳回候选口径修正（含 StartEvent、剔除 delegate 污染、去重） —————

describe('REQ20261122150750 驳回候选口径（AC-3）', () => {
  it('should_filter_delegate_pollution_dedup_and_keep_start_event_in_candidates', () => {
    // 单元口径（契约⑤）：action=ENTER 且 detail.delegate 为空 + nodeId 去重（保留首条）+ 含 StartEvent
    const candidates = rejectCandidatesOf(HIST_8806);
    expect(candidates.map((row) => row.nodeId)).toEqual(['start-1', 'task-1']);
    // 污染记录（9703 detail.delegate=boss）被剔除：task-1 唯一候选=9705（真实 ENTER）
    expect(candidates.find((row) => row.nodeId === 'task-1')?.id).toBe('9705');
  });

  it('should_keep_first_enter_when_duplicate_real_enters_for_same_node', () => {
    // 同 nodeId 两条真实 ENTER（均无 detail.delegate）：去重保留首条 9705
    const duplicated: HistoryActivityView[] = [
      ...HIST_8806,
      { id: '9706', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-22T05:00:00Z', endAt: null, detail: null },
    ];
    const candidates = rejectCandidatesOf(duplicated);
    expect(candidates.map((row) => row.nodeId)).toEqual(['start-1', 'task-1']);
    expect(candidates.find((row) => row.nodeId === 'task-1')?.id).toBe('9705');
  });

  it('should_reject_to_start_event_with_correct_payload_when_candidate_picked', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-001');
    // Drawer 内唯一 Select=驳回目标（complete 区仅键值 Input）
    const selectors = drawer.querySelectorAll('.ant-select-selector');
    expect(selectors.length, '驳回目标节点 Select 应装配').toBeGreaterThanOrEqual(1);
    fireEvent.mouseDown(selectors[0] as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '驳回目标下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
    // 候选=去重后 [start-1, task-1]：delegate 污染不产生重复项、StartEvent 在列
    expect(within(dropdown).getAllByText('start-1')).toHaveLength(1);
    expect(within(dropdown).getAllByText('task-1')).toHaveLength(1);
    fireEvent.click(within(dropdown).getByText('start-1'));
    fireEvent.click(within(drawer).getByRole('button', { name: SUBMIT_REJECT }));
    await waitFor(() => {
      const post = state.taskPosts.find((p) => p.url.pathname.endsWith('/8801/reject'));
      expect(post, 'reject 端点应被调用').toBeTruthy();
      expect(post?.body).toEqual({ targetNodeId: 'start-1', comment: null });
    });
  });
});

// ————— AC-4：Drawer 操作区按 status 与 82 分控 —————

describe('REQ20261122150750 操作区状态与权限分控（AC-4）', () => {
  it('should_show_claim_only_when_task_created', async () => {
    const { state } = await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-002');
    // CREATED 仅 claim：unclaim/complete/reject/transfer/delegate 均不渲染
    expect(within(drawer).getByRole('button', { name: BTN_CLAIM })).toBeTruthy();
    expect(within(drawer).queryByRole('button', { name: BTN_UNCLAIM })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: SUBMIT_COMPLETE })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: SUBMIT_REJECT })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: BTN_TRANSFER })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: BTN_DELEGATE })).toBeNull();
    // 认领走 Drawer 确认弹层 → claim 端点
    fireEvent.click(within(drawer).getByRole('button', { name: BTN_CLAIM }));
    await confirmPopconfirm(/确认认领该任务/);
    await waitFor(() => {
      expect(state.taskPosts.some((p) => p.url.pathname.endsWith('/8802/claim')), 'claim 端点应被调用').toBe(true);
    });
  });

  it('should_show_full_actions_when_task_claimed', async () => {
    await renderTodoPage();
    const drawer = await openApprovalDrawer('AP-20261122-001');
    // CLAIMED 显 unclaim/complete/reject/transfer/delegate（delegate 仅 CLAIMED 可见——契约⑥）
    expect(within(drawer).getByRole('button', { name: BTN_UNCLAIM })).toBeTruthy();
    expect(within(drawer).getByRole('button', { name: SUBMIT_COMPLETE })).toBeTruthy();
    expect(within(drawer).getByRole('button', { name: SUBMIT_REJECT })).toBeTruthy();
    expect(within(drawer).getByRole('button', { name: BTN_TRANSFER })).toBeTruthy();
    expect(within(drawer).getByRole('button', { name: BTN_DELEGATE })).toBeTruthy();
    expect(within(drawer).queryByRole('button', { name: BTN_CLAIM })).toBeNull();
  });

  it('should_hide_todo_row_entry_when_user_lacks_operate_permission', async () => {
    // 82 fail-closed：无 task:operate → 列表单入口「办理」不渲染（操作面整体 fail-closed）
    await renderTodoPage(PERMS_READONLY);
    expect(within(rowOf('AP-20261122-001')).queryByRole('button', { name: ACTION_COMPLETE })).toBeNull();
    expect(document.querySelector('.ant-drawer-content')).toBeNull();
  });
});

// ————— AC-5：done 只读视角 —————

describe('REQ20261122150750 已办只读视角（AC-5）', () => {
  it('should_render_done_task_readonly_without_action_buttons_when_view_clicked', async () => {
    const { state } = await renderTodoPage();
    fireEvent.click(screen.getAllByText(SEG_DONE)[0] as HTMLElement);
    await waitFor(() => {
      expect(state.doneQueries.length, 'done 分页查询应发出').toBeGreaterThanOrEqual(1);
      expect(screen.getByText('AP-20261121-003'), '已办行集应加载').toBeTruthy();
    });
    fireEvent.click(within(rowOf('AP-20261121-003')).getByRole('button', { name: ACTION_VIEW }));
    const drawer = await waitFor(() => {
      const el = drawerScope();
      expect(within(el).getByText(DRAWER_TITLE)).toBeTruthy();
      return el;
    });
    // 只读：无任何操作按钮 + 只读提示
    await waitFor(() => {
      expect(within(drawer).getByText(READONLY_HINT, { exact: false })).toBeTruthy();
    });
    expect(within(drawer).queryByRole('button', { name: BTN_CLAIM })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: SUBMIT_COMPLETE })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: SUBMIT_REJECT })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: BTN_TRANSFER })).toBeNull();
    expect(within(drawer).queryByRole('button', { name: BTN_DELEGATE })).toBeNull();
    // 历史/流程图可用
    expect(within(drawer).getByText(BLOCK_HISTORY)).toBeTruthy();
    expect(within(drawer).getByText(BLOCK_DIAGRAM)).toBeTruthy();
    await waitFor(() => {
      expect(within(drawer).getAllByText('进入').length).toBeGreaterThanOrEqual(1);
    });
  });
});

// ————— AC-6：我发起「查看」入口（复用实例详情视角） —————

describe('REQ20261122150750 我发起查看入口（AC-6）', () => {
  it('should_open_instance_detail_drawer_when_initiated_row_view_clicked', async () => {
    const { state } = await renderTodoPage();
    fireEvent.click(screen.getAllByText(SEG_INITIATED)[0] as HTMLElement);
    await waitFor(() => {
      expect(state.initiatedQueries.length, 'initiated 查询应发出').toBeGreaterThanOrEqual(1);
      expect(screen.getByText('AP-20261121-003'), '我发起行集应加载').toBeTruthy();
    });
    fireEvent.click(within(rowOf('AP-20261121-003')).getByRole('button', { name: ACTION_VIEW }));
    await waitFor(() => {
      const drawer = drawerScope();
      expect(within(drawer).getByText(DRAWER_INSTANCE), '实例详情 Drawer 应打开（复用 readonly 三件套）').toBeTruthy();
      expect(within(drawer).getByText('摘要')).toBeTruthy();
      expect(within(drawer).getByText(BLOCK_DIAGRAM)).toBeTruthy();
    });
    expect(state.detailQueries.length).toBeGreaterThanOrEqual(1);
  });
});

// i18n 触达（避免 tree-shake 语义盲区；文案锚点=zh-CN workflow 资源包）
void i18n;
