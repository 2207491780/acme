// @vitest-environment jsdom
/**
 * REQ-20261122-150750 BPM-4——msm complete 形状验证（AC-7）+ 驳回候选真实语义（契约⑤）。
 *
 * 契约口径（contracts/REQ-20261122-150750.md 确认⑤⑦ + AC-7）：
 *   - complete 请求形状验证（对齐后端 TaskCompleteRequest 校验口径）：
 *     form 非 object（字符串/数组）拒 400 VALIDATION_FAILED；comment 非字符串拒；
 *     {form:{...},comment} 与仅 {comment} 合法（AC-2 空表单仅 comment）；
 *   - reject targetNodeId 候选=历史 ENTER 且 detail.delegate 为空（含 StartEvent 历史记录，
 *     剔除 BPM-3 时代的 'start-1' 字面豁免——豁免同步为真实语义；delegate 污染 ENTER 记录不作候选）。
 *
 * MSW 接入口径照 web/tests/REQ20261122102730-workflowMock.test.ts 先例：复用交付 handlers，
 * msw/node setupServer + onUnhandledRequest:'error'；写面用例经 startUniqueInstance 自建行集
 * （BPM-3 先例：fixture 9301~9306/9401~9404 读面用例只读，complete 形状用例外——9401/9402 直接驱动）。
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import {
  ApiError, instanceApi, taskApi, useAuthStore, WORKFLOW_ERROR_CODES,
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
    accessToken: 'token-bpm4',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
});

/** 自建 CLAIMED 任务（实例 start→首任务 CREATED→认领；隔离 fixture 写面残留，BPM-3 先例）。 */
let startedSeq = 0;
async function freshClaimedTaskId(): Promise<string> {
  startedSeq += 1;
  const inst = await instanceApi.start({
    definitionId: '9002',
    businessKey: `BPM4-MOCK-${Date.now()}-${startedSeq}`,
  });
  const taskId = inst.tasks[0]?.id;
  expect(taskId, 'start 应按定义首个 userTask 生成任务').toBeTruthy();
  await taskApi.claim(taskId as string);
  return taskId as string;
}

// ————— AC-7：complete 形状验证（form 非 object 拒 400 VALIDATION_FAILED） —————

describe('REQ20261122150750 msm complete 形状验证（AC-7）', () => {
  it('should_reject_complete_with_non_object_form_when_validation_failed', async () => {
    // 形状拒绝不依赖任务状态可先于办结驱动：9401 CREATED 直接驱动
    // form=字符串（AC-7 口径：form 非 object 拒）
    try {
      await taskApi.complete('9401', { form: 'not-an-object' as never });
      throw new Error('应抛出 VALIDATION_FAILED，实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.VALIDATION_FAILED);
      expect((ex as ApiError).status).toBe(400);
    }
    // form=数组（对象但非 plain object，同样拒）
    try {
      await taskApi.complete('9401', { form: ['a', 'b'] as never });
      throw new Error('应抛出 VALIDATION_FAILED，实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.VALIDATION_FAILED);
    }
    // 9401 仍为待办（形状校验拒绝不落状态）
    const todo = await taskApi.todo(1, 20);
    expect(todo.records.some((row) => row.id === '9401'), '被拒请求不应迁移任务状态').toBe(true);
  });

  it('should_reject_complete_with_non_string_comment_when_validation_failed', async () => {
    try {
      await taskApi.complete('9401', { comment: 123 as never });
      throw new Error('应抛出 VALIDATION_FAILED，实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.VALIDATION_FAILED);
    }
  });

  it('should_accept_form_and_comment_payload_when_shape_valid', async () => {
    // 契约⑦合法形状：{form:{k:v}, comment} → 办结 + 入 done
    // （msm done 读面按 assignee=登录人过滤，先认领落 assignee 再办结——隐式认领回显由后端语义承担）
    await taskApi.claim('9401');
    await taskApi.complete('9401', { form: { amount: 1280 }, comment: '同意报销' });
    const done = await taskApi.done(1, 20);
    const doneRow = done.records.find((row) => row.id === '9401');
    expect(doneRow, '已办结任务应入 done').toBeTruthy();
    expect(doneRow?.status).toBe('COMPLETED');
  });

  it('should_accept_comment_only_payload_when_form_omitted', async () => {
    // AC-2 空表单仅 comment 合法（fixture 9402 CLAIMED）
    await taskApi.complete('9402', { comment: '仅意见' });
    const done = await taskApi.done(1, 20);
    expect(done.records.some((row) => row.id === '9402' && row.status === 'COMPLETED')).toBe(true);
  });
});

// ————— 契约⑤：reject 候选真实语义（含 StartEvent、剔除 delegate 污染、'start-1' 字面豁免剔除） —————

describe('REQ20261122150750 msm reject 候选真实语义（契约⑤）', () => {
  it('should_accept_reject_to_start_event_when_start_enter_history_exists', async () => {
    // 自建任务实例历史含 start-1 ENTER（StartEvent 合法目标；语义面替代旧 'start-1' 字面豁免）
    const taskId = await freshClaimedTaskId();
    await taskApi.reject(taskId, { targetNodeId: 'start-1' });
    const done = await taskApi.done(1, 20);
    expect(done.records.some((row) => row.id === taskId && row.status === 'REJECTED')).toBe(true);
  });

  it('should_reject_reject_target_when_node_absent_from_enter_history', async () => {
    // 无历史 ENTER 的节点 → XML_INVALID（真实语义：候选=历史 ENTER 集合，非任意 nodeId）
    const taskId = await freshClaimedTaskId();
    try {
      await taskApi.reject(taskId, { targetNodeId: 'ghost-node' });
      throw new Error('应抛出业务失败（XML_INVALID），实际成功返回');
    } catch (ex: unknown) {
      expect(ex).toBeInstanceOf(ApiError);
      expect((ex as ApiError).code).toBe(WORKFLOW_ERROR_CODES.XML_INVALID);
    }
    // 被拒请求不迁移状态：任务仍为待办
    const todo = await taskApi.todo(1, 20);
    expect(todo.records.some((row) => row.id === taskId)).toBe(true);
  });

  it('should_accept_reject_to_node_with_polluted_delegate_enter_when_real_enter_exists', async () => {
    // fixture 9306：task-1 有两条 ENTER——delegate 污染记录（9622 detail.delegate）+真实记录（9624）；
    // 候选口径=非 delegate ENTER，真实记录在列 → 驳回到 task-1 合法
    await taskApi.reject('9404', { targetNodeId: 'task-1' });
    const done = await taskApi.done(1, 20);
    expect(done.records.some((row) => row.id === '9404' && row.status === 'REJECTED')).toBe(true);
  });
});
