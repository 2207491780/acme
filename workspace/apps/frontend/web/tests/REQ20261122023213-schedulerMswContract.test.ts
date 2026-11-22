/** @vitest-environment jsdom */
/**
 * REQ-20261122-023213（JOB-3）AC-10：web msw handlers 调度中心八端点契约同形。
 *
 * 表面 = 契约 frontmatter apis + 确认记录⑦：
 *   GET  /admin/v1/scheduler/jobs          分页（page/size）+ 过滤（jobKey/status），data={records,total}；
 *   POST /admin/v1/scheduler/jobs          创建（默认 DISABLED），jobKey 冲突 → 业务信封 code=KEY_DUPLICATE；
 *   PUT  /admin/v1/scheduler/jobs/{id}     更新，job_key 不可改 → code=KEY_IMMUTABLE（乐观锁 version 透传）；
 *   PUT  /admin/v1/scheduler/jobs/{id}/status  启停专用，msw 内状态迁移（列表回读新值）；
 *   POST /admin/v1/scheduler/jobs/{id}/trigger 手动触发，返回执行 id 并落 MANUAL 执行记录；
 *   DELETE /admin/v1/scheduler/jobs/{id}   软删，列表回读不含该行；
 *   GET  /admin/v1/scheduler/executions    分页+过滤（jobKey/state/fireTimeFrom/To），record=ExecutionView 12 字段；
 *   GET  /admin/v1/scheduler/executors     执行器列表，含在线状态（ONLINE/OFFLINE）。
 *
 * MSW 接入口径（REQ20261121022910-packagesMock.test.ts / REQ20261121200415-preferencesMswContract.test.ts 先例）：
 *   - 复用项目交付的 handlers（src/mocks/handlers，与 dev mock 共用），不另写桩 handler；
 *   - msw/node setupServer + onUnhandledRequest: 'error' 钉死端点覆盖；
 *   - 直接以 fetch 驱动（端点/信封为契约锚点，不经 core api 包装层），
 *     业务失败断言信封 code 精确匹配（KEY_DUPLICATE/KEY_IMMUTABLE，契约确认⑦钉死的错误码字面量）。
 *
 * 【AMB 假设（TL 裁决锚点）】
 *   - AMB-1：PUT status 请求体目标状态字段名契约未钉死，按 JobAdminView.status 域取 { status: 'ENABLED' }；
 *   - AMB-2：msw scheduler fixtures 含 V2 种子 4 行（契约确认⑦），jobKey=file-retention-cleanup 用于冲突用例；
 *   - AMB-3：executions/executor fixture 行须含 ExecutionView 12 字段 / ONLINE+OFFLINE 双态样例（契约确认⑦形状）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '../src/mocks/handlers';

vi.setConfig({ testTimeout: 15000 });

const BASE = '/admin/v1/scheduler';
const EXECUTION_FIELDS = [
  'id', 'definitionId', 'jobKey', 'triggerType', 'fireTime', 'state',
  'attempt', 'shardIndex', 'result', 'error', 'costMs', 'traceId',
];

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

// ————— 支撑（fetch 直驱 + 信封拆包） —————

interface Envelope {
  success: boolean;
  code: string;
  message: string | null;
  data: unknown;
}

const call = async (path: string, init?: RequestInit): Promise<{ res: Response; body: Envelope }> => {
  // 原生 fetch 不解析相对 URL；补 jsdom origin 使 msw 相对路径 handler 命中同 origin
  const res = await fetch(new URL(path, window.location.origin), {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-023213', ...init?.headers },
  });
  const text = await res.text();
  // 空响应体（如 delete 204）按成功信封处理；JSON 解析失败即真错误，不吞
  const body = text.trim()
    ? JSON.parse(text) as Envelope
    : { success: res.ok, code: res.ok ? '0' : 'UNKNOWN', message: null, data: null };
  return { res, body };
};

const getJson = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path);

const postJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'POST', body: JSON.stringify(payload) });

const putJson = async (path: string, payload: unknown): Promise<{ res: Response; body: Envelope }> =>
  call(path, { method: 'PUT', body: JSON.stringify(payload) });

const del = async (path: string): Promise<{ res: Response; body: Envelope }> => call(path, { method: 'DELETE' });

/** 分页 data 形状断言（{records,total}）。 */
const expectPageData = (data: unknown): { records: Array<Record<string, unknown>>; total: number } => {
  expect(data, '分页 data 应为对象').toBeTypeOf('object');
  const page = data as { records: Array<Record<string, unknown>>; total: number };
  expect(Array.isArray(page.records)).toBe(true);
  expect(typeof page.total).toBe('number');
  return page;
};

const firstJob = async (): Promise<Record<string, unknown>> => {
  const { body } = await getJson(`${BASE}/jobs?page=1&size=10`);
  const page = expectPageData(body.data);
  expect(page.records.length, 'fixture 至少含种子任务行').toBeGreaterThan(0);
  return page.records[0]!;
};

// ————— 八端点同形 —————

describe('REQ20261122023213 msw 调度中心八端点契约同形（AC-10）', () => {
  it('should_page_and_filter_jobs_with_envelope_when_jobs_endpoint_queried', async () => {
    const { body } = await getJson(`${BASE}/jobs?page=1&size=2`);
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');
    const page = expectPageData(body.data);
    expect(page.records.length, 'size=2 应约束当页行数').toBeLessThanOrEqual(2);
    expect(page.total).toBeGreaterThan(0);

    // 过滤 jobKey 精确命中种子行
    const filtered = await getJson(`${BASE}/jobs?page=1&size=10&jobKey=file-retention-cleanup`);
    const jobPage = expectPageData(filtered.body.data);
    expect(jobPage.total).toBeGreaterThan(0);
    for (const row of jobPage.records) {
      expect(String(row.jobKey)).toContain('file-retention-cleanup');
    }

    // 过滤 status=DISABLED（V2 种子行为 DISABLED，契约背景）
    const disabled = await getJson(`${BASE}/jobs?page=1&size=10&status=DISABLED`);
    const disabledPage = expectPageData(disabled.body.data);
    expect(disabledPage.total).toBeGreaterThan(0);
    for (const row of disabledPage.records) {
      expect(row.status).toBe('DISABLED');
    }
  });

  it('should_return_key_duplicate_envelope_when_create_conflicts_existing_job_key', async () => {
    const { body } = await postJson(`${BASE}/jobs`, {
      jobKey: 'file-retention-cleanup',
      appName: 'platform-infra',
      triggerType: 'CRON',
      cron: '0 0 3 * * *',
    });
    expect(body.success, '业务冲突信封 success=false').toBe(false);
    expect(body.code, '契约确认⑦错误码字面量').toBe('KEY_DUPLICATE');
  });

  it('should_return_key_immutable_envelope_when_update_changes_job_key', async () => {
    const row = await firstJob();
    const { body } = await putJson(`${BASE}/jobs/${row.id}`, {
      ...row,
      jobKey: 'changed-job-key',
    });
    expect(body.success, 'job_key 不可改（AC-5 后端面同形）').toBe(false);
    expect(body.code, '契约确认⑦错误码字面量').toBe('KEY_IMMUTABLE');
  });

  it('should_transition_status_and_reflect_in_list_when_status_endpoint_put', async () => {
    const row = await firstJob();
    const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    const { body } = await putJson(`${BASE}/jobs/${row.id}/status`, { status: target });
    expect(body.success, '启停专用端点业务成功').toBe(true);
    expect(body.code).toBe('0');

    // msw 状态迁移：列表回读目标行状态已翻转（AMB-1：状态字段名按 JobAdminView.status）
    const after = await getJson(`${BASE}/jobs?page=1&size=50`);
    const page = expectPageData(after.body.data);
    const updated = page.records.find((item) => String(item.id) === String(row.id));
    expect(updated, '目标行仍在列表').toBeTruthy();
    expect(updated!.status).toBe(target);
  });

  it('should_return_execution_id_and_record_manual_execution_when_trigger_posted', async () => {
    const row = await firstJob();
    const { body } = await postJson(`${BASE}/jobs/${row.id}/trigger`, {});
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');
    // 返回执行 id（wire 形状两种口径兼容：裸字符串 id 或含 id/executionId 的对象）
    const data = body.data as unknown;
    expect(data, 'trigger 返回执行 id 载荷').toBeTruthy();
    const executionId = typeof data === 'object' && data !== null
      ? (data as Record<string, unknown>).id ?? (data as Record<string, unknown>).executionId
      : data;
    expect(executionId != null && String(executionId).length > 0).toBe(true);

    // MANUAL 执行记录落库（确认⑦状态迁移）：executions 首行 triggerType=MANUAL
    const executions = await getJson(`${BASE}/executions?page=1&size=10&jobKey=${String(row.jobKey)}`);
    const execPage = expectPageData(executions.body.data);
    expect(execPage.total).toBeGreaterThan(0);
    expect(execPage.records[0]!.triggerType).toBe('MANUAL');
  });

  it('should_soft_delete_and_shrink_list_when_delete_endpoint_called', async () => {
    const row = await firstJob();
    const before = expectPageData((await getJson(`${BASE}/jobs?page=1&size=50`)).body.data).total;

    const { res, body } = await del(`${BASE}/jobs/${row.id}`);
    expect([200, 204], '软删 2xx（204 空体为合法口径）').toContain(res.status);
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');

    const after = expectPageData((await getJson(`${BASE}/jobs?page=1&size=50`)).body.data);
    expect(after.total, '软删后列表回读不含该行（total 递减）').toBe(before - 1);
    expect(after.records.some((item) => String(item.id) === String(row.id))).toBe(false);
  });

  it('should_page_executions_with_filter_params_and_execution_view_shape_when_queried', async () => {
    const { body } = await getJson(
      `${BASE}/executions?page=1&size=10&jobKey=file-retention-cleanup&state=SUCCESS`
        + '&fireTimeFrom=2026-11-01T00:00:00Z&fireTimeTo=2026-11-22T23:59:59Z',
    );
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');
    const page = expectPageData(body.data);
    expect(page.records.length, 'fixture 至少含执行记录样例').toBeGreaterThan(0);
    // ExecutionView 12 字段同形（契约背景钉死字段集）
    for (const field of EXECUTION_FIELDS) {
      expect(field in page.records[0]!, `record[0] 应含 ExecutionView.${field}`).toBe(true);
    }
    // 过滤透传：命中行 jobKey 全部匹配
    for (const row of page.records) {
      expect(row.jobKey).toBe('file-retention-cleanup');
    }
  });

  it('should_list_executors_with_online_state_when_executors_endpoint_queried', async () => {
    const { body } = await getJson(`${BASE}/executors`);
    expect(body.success).toBe(true);
    expect(body.code).toBe('0');
    const payload = body.data as unknown;
    const rows: Array<Record<string, unknown>> = Array.isArray(payload)
      ? payload as Array<Record<string, unknown>>
      : (payload as { records?: Array<Record<string, unknown>> }).records ?? [];
    expect(rows.length, 'fixture 至少含执行器样例（AMB-3）').toBeGreaterThan(0);
    // 在线状态徽标数据面：fixture 须含 ONLINE/OFFLINE 双态样例（AMB-3；wire 字段名未钉死，整体串匹配）
    const serialized = JSON.stringify(rows);
    expect(serialized).toContain('ONLINE');
    expect(serialized).toContain('OFFLINE');
  });
});
