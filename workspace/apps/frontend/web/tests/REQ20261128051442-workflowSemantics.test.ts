// @vitest-environment jsdom
/**
 * REQ-20261128-051442（P1-07/P2-16）AC-9：web msm workflow handler 实例/任务域非法态语义锁死。
 *
 * 契约口径（contracts/REQ-20261128-051442-error-code-unification.md AC-9 + 附录 A.5）：
 *   - SUSPENDED 实例 terminate 成功转 TERMINATED（后端 TerminateInstanceCommand.validate 仅拒
 *     COMPLETED/TERMINATED，SUSPENDED 可终止——旧 msm 仅允许 RUNNING，为 P2-16 语义漂移点）；
 *   - COMPLETED/TERMINATED 实例的 terminate/suspend/resume 非法态操作拒绝，code 精确等于
 *     `platform-workflow-02-0002`（WORKFLOW_INSTANCE_STATE_CONFLICT；DISABLED 语义仅保留于
 *     流程定义域 01 段）；
 *   - 任务域非法态操作拒绝 code=`platform-workflow-03-0002`（WORKFLOW_TASK_STATE_CONFLICT）；
 *   - 任务归属不匹配拒绝 code=`platform-workflow-03-0003`（WORKFLOW_ASSIGNEE_MISMATCH）。
 *
 * MSW 接入口径（web/tests/REQ20261122023213-schedulerMswContract.test.ts 先例）：
 *   - 复用项目交付 handlers（src/mocks/handlers，与 dev mock 共用），msw/node setupServer +
 *     onUnhandledRequest: 'error'；直接 fetch 驱动（不经 core api 包装层），业务失败断言信封
 *     code 精确匹配附录 A.5 全码字面量（防旧符号短码 DISABLED 假绿）。
 *
 * 隔离纪律与黑盒锚点（HTTP 黑盒校准，禁读 handler 实现）：
 *   - 实例一律经 POST /process-instances 自建（新 id，businessKey 唯一化），任务操作只落自建
 *     实例的任务；定义 9002（DEPLOYED expense-claim）为只读锚点（既有先例口径），零种子行写入。
 *   - COMPLETED 实例锚点：黑盒实证「办结全部任务后实例仍 RUNNING」（handler 不建模流程到达
 *     end event 的实例完结），COMPLETED 态经自建不可达——唯一可达锚为种子终态行 9304
 *     （EXP-20261121-001）。本文件对其零写入（终态行三操作全为拒绝，无状态迁移），残留面为零。
 *   - 任务态观测面：todo/done 列表为实时 store 投影；实例详情 tasks 数组对自建行为定义派生
 *     静态投影（assignee/claim 态不回显），故归属前置经「转办后任务退出 admin todo 列表」观测。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const INSTANCES = '/admin/v1/workflow/process-instances';
const TASKS = '/admin/v1/workflow/tasks';
/** DEPLOYED 定义锚点（workflowMock/bpmApprovalMock 既有先例口径，只读）。 */
const DEFINITION_ID = '9002';
/** COMPLETED 种子终态行锚点（黑盒实证自建不可达，见文件头；只读零残留）。 */
const COMPLETED_SEED_INSTANCE_ID = '9304';

/** AC-9 钉死全码字面量（附录 A.5 WorkflowErrorCodes 02 实例 / 03 任务段）。 */
const CODE_INSTANCE_STATE_CONFLICT = 'platform-workflow-02-0002';
const CODE_TASK_STATE_CONFLICT = 'platform-workflow-03-0002';
const CODE_ASSIGNEE_MISMATCH = 'platform-workflow-03-0003';

const server = setupServer(...handlers);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

// ————— 支撑（fetch 直驱 + 信封拆包，schedulerMswContract 范本口径） —————

interface Envelope {
  success: boolean;
  code: string;
  message: string | null;
  data: unknown;
}

type Row = Record<string, unknown>;

const call = async (path: string, init?: RequestInit): Promise<{ res: Response; body: Envelope }> => {
  // 原生 fetch 不解析相对 URL；补 jsdom origin 使 msw 相对路径 handler 命中同 origin
  const res = await fetch(new URL(path, window.location.origin), {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-20261128051442', ...init?.headers },
  });
  const text = await res.text();
  // 空响应体（既有 handler suspend/transfer 等返回空体为已知口径）按成功/失败信封兜底
  const body = text.trim()
    ? JSON.parse(text) as Envelope
    : { success: res.ok, code: res.ok ? '0' : 'UNKNOWN', message: null, data: null };
  return { res, body };
};

const getJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path);

const post = async (path: string, payload?: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'POST', body: payload === undefined ? undefined : JSON.stringify(payload) });

const str = (v: unknown): string => (v == null ? '' : String(v));

const recordsOf = (data: unknown): Row[] => {
  expect(data, '分页 data 应为对象').toBeTypeOf('object');
  const page = data as { records?: unknown };
  expect(Array.isArray(page.records), '分页 data.records 应为数组').toBe(true);
  return page.records as Row[];
};

const tasksOf = (row: Row): Row[] => (Array.isArray(row.tasks) ? row.tasks as Row[] : []);

// ————— 实例/任务驱动（写面全部落自建行集） —————

let seq = 0;

const startInstance = async (tag: string): Promise<Row> => {
  seq += 1;
  const businessKey = `REQ051442-${tag}-${Date.now()}-${seq}`;
  const { body } = await post(INSTANCES, { definitionId: DEFINITION_ID, businessKey, variables: { amount: 100 } });
  expect(body.success, '实例发起业务成功').toBe(true);
  expect(body.code).toBe('0');
  const data = body.data as Row | null;
  expect(data, '发起应回显实例视图').toBeTruthy();
  expect(str(data?.id), '实例视图应携带 id').not.toBe('');
  expect(str(data?.status), '发起实例应为 RUNNING').toBe('RUNNING');
  return data as Row;
};

const instanceDetail = async (id: string): Promise<Row> => {
  const { body } = await getJson(`${INSTANCES}/${id}`);
  expect(body.success, '实例详情业务成功').toBe(true);
  expect(body.code).toBe('0');
  const data = body.data as Row | null;
  expect(data, `实例 ${id} 详情应存在`).toBeTruthy();
  return data as Row;
};

const todoIds = async (): Promise<Set<string>> => {
  const { body } = await getJson(`${TASKS}/todo?page=1&size=50`);
  expect(body.success, '待办列表业务成功').toBe(true);
  return new Set(recordsOf(body.data).map((row) => str(row.id)));
};

const doneRowOf = async (taskId: string): Promise<Row | undefined> => {
  const { body } = await getJson(`${TASKS}/done?page=1&size=50`);
  expect(body.success, '已办列表业务成功').toBe(true);
  return recordsOf(body.data).find((row) => str(row.id) === taskId);
};

const suspendInstance = async (id: string, reason: string): Promise<{ res: Response; body: Envelope }> =>
  post(`${INSTANCES}/${id}/suspend`, { reason });

const terminateInstance = async (id: string): Promise<{ res: Response; body: Envelope }> =>
  post(`${INSTANCES}/${id}/terminate`, {});

const resumeInstance = async (id: string): Promise<{ res: Response; body: Envelope }> =>
  post(`${INSTANCES}/${id}/resume`, {});

const claimTask = async (taskId: string): Promise<{ res: Response; body: Envelope }> =>
  post(`${TASKS}/${taskId}/claim`, {});

const completeTask = async (taskId: string): Promise<{ res: Response; body: Envelope }> =>
  post(`${TASKS}/${taskId}/complete`, { comment: 'REQ051442 办结' });

/** TERMINATED 前置（自建可达）：RUNNING 实例终止落终态。 */
const terminateFreshInstance = async (tag: string): Promise<string> => {
  const inst = await startInstance(tag);
  const id = str(inst.id);
  const term = await terminateInstance(id);
  expect(term.body.success, 'RUNNING terminate 业务成功').toBe(true);
  expect(str((await instanceDetail(id)).status), '终止后实例应 TERMINATED').toBe('TERMINATED');
  return id;
};

// ————— AC-9：实例域状态机语义 —————

describe('REQ20261128051442 workflow 实例域非法态语义（AC-9）', () => {
  it('should_terminate_suspended_instance_when_terminate_invoked', async () => {
    const inst = await startInstance('susp-term');
    const id = str(inst.id);

    const suspended = await suspendInstance(id, 'REQ051442 挂起窗口');
    expect(suspended.body.success, 'RUNNING 挂起业务成功').toBe(true);
    expect(str((await instanceDetail(id)).status), '挂起后应 SUSPENDED').toBe('SUSPENDED');

    // 目标语义：SUSPENDED 可终止（后端仅拒 COMPLETED/TERMINATED；旧 msm 仅允许 RUNNING=漂移点）
    const { body } = await terminateInstance(id);
    expect(body.success, 'SUSPENDED terminate 应成功（P2-16 对齐后端状态机）').toBe(true);
    expect(body.code).toBe('0');

    const after = await instanceDetail(id);
    expect(str(after.status), 'SUSPENDED terminate 应转 TERMINATED').toBe('TERMINATED');
    expect(str(after.endTime), '终止应落 endTime').not.toBe('');
  });

  it('should_reject_terminal_operations_with_instance_state_conflict_when_instance_terminated', async () => {
    const id = await terminateFreshInstance('term-neg');

    // TERMINATED 实例三操作全拒，code 精确等于实例态冲突全码（旧 msm 发 DISABLED=旧口径判别点）
    const reTerm = await terminateInstance(id);
    expect(reTerm.body.success, 'TERMINATED 不可再终止').toBe(false);
    expect(reTerm.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    const susp = await suspendInstance(id, '终态挂起尝试');
    expect(susp.body.success, 'TERMINATED 不可挂起').toBe(false);
    expect(susp.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    const resumed = await resumeInstance(id);
    expect(resumed.body.success, 'TERMINATED 不可恢复').toBe(false);
    expect(resumed.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    // 被拒操作不得迁移状态（非法输入不覆盖有效状态）
    expect(str((await instanceDetail(id)).status), '被拒操作后仍应 TERMINATED').toBe('TERMINATED');
  });

  it('should_reject_terminal_operations_with_instance_state_conflict_when_instance_completed', async () => {
    // COMPLETED 前置锚（文件头黑盒校准说明）：终态种子行只读锚定，零写入零残留
    const detail = await instanceDetail(COMPLETED_SEED_INSTANCE_ID);
    expect(str(detail.status), '前置：锚行应为 COMPLETED 实例').toBe('COMPLETED');

    const term = await terminateInstance(COMPLETED_SEED_INSTANCE_ID);
    expect(term.body.success, 'COMPLETED 不可终止').toBe(false);
    expect(term.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    const susp = await suspendInstance(COMPLETED_SEED_INSTANCE_ID, '终态挂起尝试');
    expect(susp.body.success, 'COMPLETED 不可挂起').toBe(false);
    expect(susp.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    const resumed = await resumeInstance(COMPLETED_SEED_INSTANCE_ID);
    expect(resumed.body.success, 'COMPLETED 不可恢复').toBe(false);
    expect(resumed.body.code, 'AC-9 钉死全码（附录 A.5 02-0002）').toBe(CODE_INSTANCE_STATE_CONFLICT);

    // 被拒操作不得迁移状态（锚行保持 COMPLETED，残留面为零）
    expect(str((await instanceDetail(COMPLETED_SEED_INSTANCE_ID)).status), '被拒操作后锚行仍 COMPLETED')
      .toBe('COMPLETED');
  });
});

// ————— AC-9：任务域状态机与归属语义 —————

describe('REQ20261128051442 workflow 任务域非法态/归属语义（AC-9）', () => {
  it('should_reject_completed_task_operations_with_task_state_conflict_when_task_terminal', async () => {
    const inst = await startInstance('task-neg');
    const taskId = str(tasksOf(inst)[0]?.id);
    expect(taskId, '自建实例应携带首个活跃任务').not.toBe('');

    await claimTask(taskId);
    const done = await completeTask(taskId);
    expect(done.body.success, 'CREATED 认领后办结业务成功').toBe(true);
    const doneRow = await doneRowOf(taskId);
    expect(doneRow, '办结任务应入 done').toBeTruthy();
    expect(str(doneRow?.status), '前置：任务应 COMPLETED').toBe('COMPLETED');

    // 非法态负例：COMPLETED 任务再办结/再驳回 → 任务态冲突全码（旧 msm 发 DISABLED=旧口径判别点）
    const again = await completeTask(taskId);
    expect(again.body.success, 'COMPLETED 任务不可再办结').toBe(false);
    expect(again.body.code, 'AC-9 钉死全码（附录 A.5 03-0002）').toBe(CODE_TASK_STATE_CONFLICT);

    const rej = await post(`${TASKS}/${taskId}/reject`, { targetNodeId: 'start-1', comment: '重复驳回' });
    expect(rej.body.success, 'COMPLETED 任务不可再驳回').toBe(false);
    expect(rej.body.code, 'AC-9 钉死全码（附录 A.5 03-0002）').toBe(CODE_TASK_STATE_CONFLICT);

    // 被拒操作不得迁移状态：任务仍在 done 且保持 COMPLETED
    const settled = await doneRowOf(taskId);
    expect(str(settled?.status), '被拒操作后任务仍 COMPLETED').toBe('COMPLETED');
  });

  it('should_reject_complete_with_assignee_mismatch_code_when_task_owned_by_other', async () => {
    const inst = await startInstance('assignee');
    const taskId = str(tasksOf(inst)[0]?.id);
    expect(taskId, '自建实例应携带首个活跃任务').not.toBe('');

    // 归属改造（黑盒观测面=todo 列表，文件头说明）：admin 认领 → 转办 ops → 任务退出 admin todo
    await claimTask(taskId);
    expect((await todoIds()).has(taskId), '前置：认领后任务应在 admin 待办').toBe(true);
    const transferred = await post(`${TASKS}/${taskId}/transfer`, { assignee: 'ops' });
    expect(transferred.body.success, '转办业务成功').toBe(true);
    expect((await todoIds()).has(taskId), '前置：转办后任务应退出 admin 待办（归属已易主）').toBe(false);

    // 归属不匹配负例：非归属人（默认会话身份）办结 → 归属冲突全码，且任务不被迁移
    const comp = await completeTask(taskId);
    expect(comp.body.success, '非归属人办结应拒绝').toBe(false);
    expect(comp.body.code, 'AC-9 钉死全码（附录 A.5 03-0003）').toBe(CODE_ASSIGNEE_MISMATCH);
    expect(await doneRowOf(taskId), '被拒办结不得将任务迁入 done').toBeUndefined();
  });
});
