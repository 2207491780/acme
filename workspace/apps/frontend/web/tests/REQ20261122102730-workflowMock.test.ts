// @vitest-environment jsdom
/**
 * REQ-20261122-102730 BPM-3——msw 实例/任务域端点同形契约用例（AC-9）。
 *
 * 契约口径（contracts/REQ-20261122-102730.md apis 面 + 确认⑤⑧ + AC-9）：
 *   - 实例域 8 端点：page（definitionKey/status/businessKey 过滤）/initiated/detail/history/
 *     start/terminate/suspend/resume；任务域 8 端点：todo/done（分页+可选过滤）/complete/
 *     reject(targetNodeId?,comment?)/claim/unclaim/transfer(assignee)/delegate（委托链单层）；
 *   - wire id 一律字符串（定义域先例延续）；PageResult 分页（records/total）；
 *   - todo/done/initiated 行带投影四字段 definitionKey/definitionName/businessKey/initiator
 *     （契约⑤，前端禁 N+1 二次取详情）；实例详情全量回显 variables/activeNodes/tasks；
 *   - history 非分页 List（ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE），REJECT_EDGE nodeId=sequenceFlow id
 *     （readOnly 流程图边标红定位面）。
 *
 * MSW 接入口径照 web/tests/REQ20261122023441-workflowMock.test.ts 先例：复用交付 handlers，
 * msw/node setupServer + onUnhandledRequest:'error'。
 * 隔离纪律（BPM-2 先例）：fixture 9301~9305/9401~9403 只读不写（读面用例全部经真实 handlers）；
 * 写面端点（start/terminate/suspend/resume + complete/reject/claim/unclaim/transfer/delegate）
 * 无幂等创建入口可自建行集，一律 server.use 覆盖桩断言「端点 URL+载荷」同形（照 BPM-2 deploy 409
 * 覆盖桩先例；fixture 状态零变更，handler 写面行为由页面联调与 TL 全量门禁承载）。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  ApiError,
  instanceApi,
  taskApi,
  useAuthStore,
  WORKFLOW_ERROR_CODES,
  WORKFLOW_INSTANCE_ENDPOINTS,
  WORKFLOW_TASK_ENDPOINTS,
} from '@acme/core';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
});

afterAll(() => {
  server.close();
});

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.getState().setSession({
    accessToken: 'token-bpm3',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

/** wire 字符串 id 断言（定义域先例延续：实例/任务/历史 id 均非空文本）。 */
const expectWireStringId = (row: { id?: unknown }, desc: string): void => {
  expect(typeof row.id, `${desc} wire id 应为字符串`).toBe('string');
  expect((row.id as string).length, `${desc} wire id 应非空`).toBeGreaterThan(0);
};

// ————— AC-9：实例域读面（真实 handlers + fixtures 只读） —————

describe('REQ20261122102730 实例域同形（AC-9 读面）', () => {
  it('should_page_instances_with_wire_string_id_and_projection_when_filtered', async () => {
    // ① 分页形状 + wire 字符串 id + 投影字段随行
    const page = await instanceApi.page(1, 20);
    expect(page.total).toBeGreaterThanOrEqual(5);
    expect(Array.isArray(page.records)).toBe(true);
    for (const row of page.records) {
      expectWireStringId(row, '实例行');
      expect(row.definitionKey, '实例行 definitionKey 投影').toBe('expense-claim');
      expect(row.definitionName, '实例行 definitionName 投影').toBe('报销申请');
      expect(row.businessKey, '实例行 businessKey 投影').toBeTruthy();
      expect(row.initiator, '实例行 initiator 投影').toBeTruthy();
    }
    // ② status 精确过滤（状态机四态）
    const running = await instanceApi.page(1, 20, { status: 'RUNNING' });
    expect(running.records.every((row) => row.status === 'RUNNING')).toBe(true);
    const suspended = await instanceApi.page(1, 20, { status: 'SUSPENDED' });
    expect(suspended.records).toHaveLength(1);
    expect(suspended.records[0]?.id).toBe('9303');
    expect(suspended.records[0]?.suspendReason).toBe('SERVICE_TASK_FAILED');
    // ③ definitionKey 精确过滤
    const byKey = await instanceApi.page(1, 20, { definitionKey: 'expense-claim' });
    expect(byKey.total).toBe(page.total);
    // ④ businessKey 精确过滤（唯一命中）
    const byBk = await instanceApi.page(1, 20, { businessKey: 'EXP-20261122-001' });
    expect(byBk.total).toBe(1);
    expect(byBk.records[0]?.id).toBe('9301');
  });

  it('should_echo_full_detail_view_when_instance_fetched', async () => {
    const detail = await instanceApi.get('9301');
    expectWireStringId(detail, '实例详情');
    expect(detail.status).toBe('RUNNING');
    expect(detail.businessKey).toBe('EXP-20261122-001');
    expect(detail.initiator).toBe('admin');
    expect(detail.endTime ?? null).toBeNull();
    // 详情全量回显：变量摘要 + 活跃节点 + 活动任务
    expect(detail.variables).toEqual({ amount: 1280, reason: '差旅报销' });
    expect(detail.activeNodes).toHaveLength(1);
    expect(detail.activeNodes[0]?.nodeId).toBe('task-1');
    expect(detail.activeNodes[0]?.nodeName).toBe('主管审批');
    expect(detail.tasks).toHaveLength(1);
    expect(detail.tasks[0]?.id).toBe('9401');
    expect(detail.tasks[0]?.status).toBe('CREATED');
  });

  it('should_return_history_list_with_actions_when_instance_history_fetched', async () => {
    // 非分页 List：ENTER/LEAVE/REJECT_EDGE 动作齐见；REJECT_EDGE nodeId=sequenceFlow id（边标红面）
    const history = await instanceApi.history('9301');
    expect(Array.isArray(history)).toBe(true);
    expect(history.length).toBeGreaterThanOrEqual(4);
    for (const item of history) {
      expectWireStringId(item, '历史条目');
      expect(['ENTER', 'LEAVE', 'REJECT_EDGE', 'TIMER_FIRE']).toContain(item.action);
    }
    const rejectEdge = history.find((item) => item.action === 'REJECT_EDGE');
    expect(rejectEdge, 'REJECT_EDGE 条目应存在').toBeTruthy();
    expect(rejectEdge?.nodeId).toBe('flow-2');
    const actions = new Set(history.map((item) => item.action));
    expect(actions.has('ENTER')).toBe(true);
    expect(actions.has('LEAVE')).toBe(true);
  });

  it('should_page_initiated_instances_with_projection_when_initiated_queried', async () => {
    // 我发起子区：投影 definitionName/businessKey/initiator 随行（契约⑤禁前端 N+1）
    const page = await instanceApi.initiated(1, 20);
    expect(Array.isArray(page.records)).toBe(true);
    expect(page.records.length, '我发起实例应有行集').toBeGreaterThanOrEqual(1);
    for (const row of page.records) {
      expectWireStringId(row, '我发起行');
      expect(row.definitionName).toBe('报销申请');
      expect(row.businessKey).toBeTruthy();
      expect(row.initiator).toBeTruthy();
    }
    expect(page.records.some((row) => row.businessKey === 'EXP-20261122-001')).toBe(true);
  });
});

// ————— AC-9：任务域读面（真实 handlers + fixtures 只读） —————

describe('REQ20261122102730 任务域同形（AC-9 读面）', () => {
  it('should_page_todo_tasks_with_projection_and_wire_string_id_when_todo_queried', async () => {
    const page = await taskApi.todo(1, 20);
    expect(Array.isArray(page.records)).toBe(true);
    expect(page.records.length, '待办任务应有行集').toBeGreaterThanOrEqual(1);
    for (const row of page.records) {
      expectWireStringId(row, '待办行');
      // 契约⑤投影四字段全量回显（前端禁 N+1 二次取详情）
      expect(row.definitionKey).toBe('expense-claim');
      expect(row.definitionName).toBe('报销申请');
      expect(row.businessKey).toBeTruthy();
      expect(row.initiator).toBeTruthy();
      expect(['CREATED', 'CLAIMED']).toContain(row.status);
    }
    expect(page.records.some((row) => row.id === '9401' && row.status === 'CREATED')).toBe(true);
    expect(page.records.some((row) => row.id === '9402' && row.status === 'CLAIMED')).toBe(true);
  });

  it('should_page_done_tasks_when_done_queried', async () => {
    const page = await taskApi.done(1, 20);
    expect(Array.isArray(page.records)).toBe(true);
    const completed = page.records.find((row) => row.id === '9403');
    expect(completed, '已办结任务应入 done').toBeTruthy();
    expect(completed?.status).toBe('COMPLETED');
    expect(completed?.completeTime).toBeTruthy();
    expect(completed?.definitionName).toBe('报销申请');
    expect(completed?.businessKey).toBe('EXP-20261121-001');
  });

  it('should_filter_todo_by_definition_key_and_status_when_params_passed', async () => {
    // 契约⑤：todo/done 可选过滤 definitionKey/status（wire 透传，mock 侧同形处理）
    const byKey = await taskApi.todo(1, 20, { definitionKey: 'expense-claim' });
    expect(byKey.records.every((row) => row.definitionKey === 'expense-claim')).toBe(true);
    const byStatus = await taskApi.todo(1, 20, { status: 'CREATED' });
    expect(byStatus.records.length).toBeGreaterThanOrEqual(1);
    expect(byStatus.records.every((row) => row.status === 'CREATED')).toBe(true);
    const miss = await taskApi.todo(1, 20, { definitionKey: `zzz-无命中-${Date.now()}` });
    expect(miss.total).toBe(0);
    expect(miss.records).toHaveLength(0);
  });
});

// ————— AC-9：写面同形（真实 handler 状态机闭环为主；分歧端点覆盖桩断言载荷面） —————

/**
 * 隔离与顺序纪律：fixture 9301~9305/9401~9403 只读不写——写面用例一律先经 instanceApi.start
 * 自建实例（自建行带 CREATED 任务），在自建行集上闭环；fixture 任务仅 9401 参与 claim/unclaim
 * （终态还原），读面 describe 先于写面执行（文件内顺序），互不污染。
 *
 * 已知分歧（上报 TL，红判明）：mock handler 的 suspend/transfer/delegate 返回空体（empty()），
 * 而 core client 对应方法走 apiJson（消费信封）→ EMPTY_BODY。三端点经覆盖桩断言载荷面
 * （BPM-2 deploy 409 覆盖桩先例）；handler 与 client 何者对齐后端真实形态由 TL 裁决。
 */
describe('REQ20261122102730 写面同形（AC-9 写面）', () => {
  let seq = 0;
  const uniqBk = (suffix: string): string => `bpm3it-${Date.now()}-${++seq}-${suffix}`;

  /** 自建实例（DEPLOYED 定义 9002）→ 返回实例视图（含首个 userTask 的 CREATED 任务）。 */
  const startUniqueInstance = async (suffix: string): Promise<ProcessInstanceLike> => {
    const started = await instanceApi.start({
      definitionId: '9002',
      businessKey: uniqBk(suffix),
      variables: { amount: 100 },
    });
    expect(started.status, '自建实例应为 RUNNING').toBe('RUNNING');
    return started;
  };

  interface ProcessInstanceLike {
    id: string;
    status: string;
    initiator: string | null;
    businessKey: string | null;
    tasks: { id: string; status: string; assignee: string | null }[];
  }

  it('should_start_instance_with_created_task_when_deployed_definition_started', async () => {
    const bk = uniqBk('start');
    const started = await instanceApi.start({
      definitionId: '9002',
      businessKey: bk,
      variables: { amount: 100 },
    });
    // 发起返回 RUNNING 实例（wire 字符串 id）+ 首个 userTask 的 CREATED 任务（投影随行）
    expect(typeof started.id).toBe('string');
    expect(started.status).toBe('RUNNING');
    expect(started.initiator).toBe('admin');
    expect(started.tasks).toHaveLength(1);
    expect(started.tasks[0]?.status).toBe('CREATED');
    expect(started.tasks[0]?.assignee ?? null).toBeNull();
    // 分页过滤可定位自建实例（handler 落库面）
    const page = await instanceApi.page(1, 20, { businessKey: bk });
    expect(page.total).toBe(1);
    // 自建实例入「我发起」（initiator=登录人）
    const initiated = await instanceApi.initiated(1, 20);
    expect(initiated.records.some((row) => row.businessKey === bk)).toBe(true);
    // DRAFT 定义不可发起（状态机负例：业务错误信封）
    try {
      await instanceApi.start({ definitionId: '9001', businessKey: uniqBk('draft-start') });
      throw new Error('应抛出业务失败（DISABLED），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.DISABLED);
    }
  });

  it('should_claim_then_unclaim_when_todo_task_claimed_and_released', async () => {
    // fixture 9401（CREATED/assignee=null，候选可见）→ claim → CLAIMED（assignee=登录人）
    await taskApi.claim('9401');
    const claimed = await taskApi.todo(1, 20);
    const claimedRow = claimed.records.find((row) => row.id === '9401');
    expect(claimedRow, '认领后仍应在我的待办').toBeTruthy();
    expect(claimedRow?.status).toBe('CLAIMED');
    expect(claimedRow?.assignee).toBe('admin');
    // unclaim → 还原 CREATED/assignee 清空（终态还原，fixture 零残留）
    await taskApi.unclaim('9401');
    const released = await taskApi.todo(1, 20);
    const releasedRow = released.records.find((row) => row.id === '9401');
    expect(releasedRow?.status).toBe('CREATED');
    expect(releasedRow?.assignee ?? null).toBeNull();
  });

  it('should_complete_claimed_task_when_complete_invoked', async () => {
    const started = await startUniqueInstance('complete');
    const taskId = started.tasks[0]?.id as string;
    await taskApi.claim(taskId);
    await taskApi.complete(taskId, { comment: '同意报销' });
    // 已办结任务退出待办、入 done（COMPLETED + completeTime）
    const todo = await taskApi.todo(1, 20);
    expect(todo.records.some((row) => row.id === taskId), '已办结任务应退出待办').toBe(false);
    const done = await taskApi.done(1, 20);
    const doneRow = done.records.find((row) => row.id === taskId);
    expect(doneRow, '已办结任务应入 done').toBeTruthy();
    expect(doneRow?.status).toBe('COMPLETED');
    expect(doneRow?.completeTime).toBeTruthy();
    // 状态机负例：已办结任务不可再驳回（AC-9 任务域状态冲突=platform-workflow-03-0002）
    try {
      await taskApi.reject(taskId, { comment: '重复驳回' });
      throw new Error('应抛出业务失败（TASK_STATE_CONFLICT），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.TASK_STATE_CONFLICT);
    }
  });

  it('should_reject_task_with_target_node_when_reject_invoked', async () => {
    const started = await startUniqueInstance('reject');
    const taskId = started.tasks[0]?.id as string;
    await taskApi.claim(taskId);
    // targetNodeId=驳回指定节点（自建实例历史 ENTER start-1 命中候选）；REJECTED 入 done
    await taskApi.reject(taskId, { targetNodeId: 'start-1', comment: '金额超限退回' });
    const todo = await taskApi.todo(1, 20);
    expect(todo.records.some((row) => row.id === taskId), '已驳回任务应退出待办').toBe(false);
    const done = await taskApi.done(1, 20);
    const doneRow = done.records.find((row) => row.id === taskId);
    expect(doneRow?.status).toBe('REJECTED');
    // 非法 targetNodeId → 业务错误信封（mock 校验面与后端同形）
    const other = await startUniqueInstance('reject-bad');
    const otherTaskId = other.tasks[0]?.id as string;
    try {
      await taskApi.reject(otherTaskId, { targetNodeId: 'ghost-node' });
      throw new Error('应抛出业务失败（XML_INVALID），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.XML_INVALID);
    }
  });

  it('should_terminate_running_instance_when_terminate_invoked', async () => {
    const started = await startUniqueInstance('terminate');
    await instanceApi.terminate(started.id);
    // TERMINATED + endTime 落定（分页过滤定位）
    const page = await instanceApi.page(1, 20, { businessKey: started.businessKey });
    expect(page.records[0]?.status).toBe('TERMINATED');
    expect(page.records[0]?.endTime).toBeTruthy();
    // 状态机负例：TERMINATED 不可再终止（AC-9 实例域状态冲突=platform-workflow-02-0002）
    try {
      await instanceApi.terminate(started.id);
      throw new Error('应抛出业务失败（INSTANCE_STATE_CONFLICT），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.INSTANCE_STATE_CONFLICT);
    }
  });

  it('should_resume_suspended_instance_when_resume_invoked', async () => {
    // fixture 9303（SUSPENDED 种子，只读用例不挂起自建实例规避 suspend 分歧）→ 恢复
    await instanceApi.resume('9303');
    const page = await instanceApi.page(1, 20, { businessKey: 'EXP-20261122-003' });
    expect(page.records[0]?.status).toBe('RUNNING');
    expect(page.records[0]?.suspendReason ?? null).toBeNull();
  });

  it('should_suspend_running_instance_when_suspend_invoked', async () => {
    // 契约意图断言：suspend(reason) 应被 core client 正常消费（信封或 void 双口径之一，
    // 与 terminate/resume 消费面一致）。已知分歧（上报 TL）：mock handler 返回空体而
    // core api suspend 走 apiJson 信封消费 → EMPTY_BODY；对齐后本用例即绿。
    const started = await startUniqueInstance('suspend');
    await instanceApi.suspend(started.id, '运维窗口挂起');
    const page = await instanceApi.page(1, 20, { businessKey: started.businessKey });
    expect(page.records[0]?.status).toBe('SUSPENDED');
    expect(page.records[0]?.suspendReason).toBe('运维窗口挂起');
  });

  it('should_transfer_and_delegate_with_assignee_payload_when_invoked', async () => {
    // 分歧端点（transfer/delegate）覆盖桩：断言 URL+assignee 载荷同形（委托链单层）
    const started = await startUniqueInstance('assign');
    const taskId = started.tasks[0]?.id as string;
    const posted: { pathname: string; body: unknown }[] = [];
    const envelopeStub = (pathname: string): void => {
      server.use(http.post(pathname, async ({ request }) => {
        const raw = await request.text();
        let body: unknown = null;
        try { body = raw ? JSON.parse(raw) : null; } catch { body = raw; }
        posted.push({ pathname: new URL(request.url).pathname, body });
        return HttpResponse.json({ success: true, code: '0', message: null, data: null });
      }));
    };
    envelopeStub(WORKFLOW_TASK_ENDPOINTS.transfer(taskId));
    envelopeStub(WORKFLOW_TASK_ENDPOINTS.delegate(taskId));
    await taskApi.transfer(taskId, { assignee: 'ops' });
    await taskApi.delegate(taskId, { assignee: 'ops2' });
    expect(posted.map((p) => p.pathname)).toEqual([
      WORKFLOW_TASK_ENDPOINTS.transfer(taskId),
      WORKFLOW_TASK_ENDPOINTS.delegate(taskId),
    ]);
    expect(posted[0]?.body).toEqual({ assignee: 'ops' });
    expect(posted[1]?.body).toEqual({ assignee: 'ops2' });
  });

  it('should_surface_business_error_envelope_when_instance_operation_rejected', async () => {
    // 业务失败信封（success=false, code≠0）→ ApiError 拆包（统一响应体口径，勿断言 4xx 信封之外形态）
    server.use(http.post(WORKFLOW_INSTANCE_ENDPOINTS.suspend('9302'), () => HttpResponse.json({
      success: false, code: WORKFLOW_ERROR_CODES.INSTANCE_NOT_FOUND, message: '流程实例不存在', data: null,
    }, { status: 404 })));
    try {
      await instanceApi.suspend('9302', '运维窗口挂起');
      throw new Error('应抛出业务失败（INSTANCE_NOT_FOUND），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.INSTANCE_NOT_FOUND);
    }
  });
});
