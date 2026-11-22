import { http } from 'msw';
import * as db from '../data';
import { bodyOf, empty, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';
import type { ExecutionView, ExecutorView, JobAdminView, JobStatus } from '@acme/core';

/**
 * 调度中心域 handler（REQ-20261122-023213 JOB-3，八端点与后端同形）：
 * 任务定义分页/创建（KEY_DUPLICATE/默认 DISABLED）/编辑（KEY_IMMUTABLE+version）/启停/触发（MANUAL）/
 * 软删 + 执行历史分页（jobKey/state/fireTime 窗口，fire_time 倒序）+ 执行器清单（ONLINE/OFFLINE）。
 * resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。
 */

const store = {
  jobs: [...db.schedulerJobs],
  executions: [...db.schedulerExecutions],
  executors: [...db.schedulerExecutors],
};

let nextId = 9700;
const genId = (): string => String(nextId++);
const nowIso = (): string => new Date().toISOString();

/** 合法任务状态（/status 专用端点校验面）。 */
const isJobStatus = (value: unknown): value is JobStatus => value === 'ENABLED' || value === 'DISABLED';

export const schedulerHandlers = [
  http.get('/admin/v1/scheduler/jobs', ({ request }) => {
    const params = urlOf(request).searchParams;
    const jobKey = params.get('jobKey');
    const appName = params.get('appName');
    const status = params.get('status');
    const rows = store.jobs.filter((job) => {
      if (jobKey && !job.jobKey.includes(jobKey)) return false;
      if (appName && job.appName !== appName) return false;
      if (status && job.status !== status) return false;
      return true;
    });
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/scheduler/jobs', async ({ request }) => {
    const body = await bodyOf(request);
    const jobKey = typeof body.jobKey === 'string' ? body.jobKey : '';
    const appName = typeof body.appName === 'string' ? body.appName : '';
    if (!jobKey || !appName) {
      return fail('PARAM_INVALID', 'jobKey/appName 必填', 400);
    }
    if (store.jobs.some((job) => job.jobKey === jobKey)) {
      return fail('KEY_DUPLICATE', '任务标识已存在', 400);
    }
    const created: JobAdminView = {
      id: genId(),
      jobKey,
      appName,
      handler: typeof body.handler === 'string' ? body.handler : null,
      triggerType: body.triggerType === 'FIXED_RATE' || body.triggerType === 'ONCE' ? body.triggerType : 'CRON',
      cron: typeof body.cron === 'string' ? body.cron : null,
      fixedRate: typeof body.fixedRate === 'number' ? body.fixedRate : null,
      onceFireAt: typeof body.onceFireAt === 'string' ? body.onceFireAt : null,
      param: (body.param && typeof body.param === 'object' ? body.param : null) as Record<string, unknown> | null,
      retryPolicy: (body.retryPolicy && typeof body.retryPolicy === 'object' ? body.retryPolicy : null) as JobAdminView['retryPolicy'],
      shardTotal: typeof body.shardTotal === 'number' && body.shardTotal >= 1 ? Math.floor(body.shardTotal) : 1,
      timeoutMs: typeof body.timeoutMs === 'number' ? body.timeoutMs : null,
      misfirePolicy: body.misfirePolicy === 'IGNORE' ? 'IGNORE' : 'FIRE_ONCE_NOW',
      // 契约：创建默认 DISABLED（显式启用才入时间轮）
      status: 'DISABLED',
      nextFireTime: null,
      createdAt: nowIso(),
      version: '0',
    };
    store.jobs = [created, ...store.jobs];
    return ok(created);
  }),
  http.put('/admin/v1/scheduler/jobs/:jobId/status', async ({ request, params }) => {
    const target = store.jobs.find((job) => job.id === String(params.jobId));
    if (!target) {
      return fail('JOB_NOT_FOUND', '任务不存在', 404);
    }
    const body = await bodyOf(request);
    if (!isJobStatus(body.status)) {
      return fail('PARAM_INVALID', 'status 必须为 ENABLED/DISABLED', 400);
    }
    const status = body.status;
    store.jobs = store.jobs.map((job) =>
      job.id === target.id
        ? {
            ...job,
            status,
            // 启用=算初始排期回填时间轮（mock 语义：nextFireTime 取当前时刻近似）；停用=removeWheel（置空）
            nextFireTime: status === 'ENABLED' ? nowIso() : null,
            version: String(Number(job.version) + 1),
          }
        : job,
    );
    const updated = store.jobs.find((job) => job.id === target.id)!;
    return ok(updated);
  }),
  http.put('/admin/v1/scheduler/jobs/:jobId', async ({ request, params }) => {
    const target = store.jobs.find((job) => job.id === String(params.jobId));
    if (!target) {
      return fail('JOB_NOT_FOUND', '任务不存在', 404);
    }
    const body = await bodyOf(request);
    // job_key 创建后不可改（KEY_IMMUTABLE：随体携带且与现值不符即拒绝）
    if (typeof body.jobKey === 'string' && body.jobKey !== target.jobKey) {
      return fail('KEY_IMMUTABLE', '任务标识不可修改', 400);
    }
    store.jobs = store.jobs.map((job) =>
      job.id === target.id
        ? {
            ...job,
            appName: typeof body.appName === 'string' ? body.appName : job.appName,
            handler: body.handler === undefined ? job.handler : (body.handler as string | null),
            triggerType: body.triggerType === 'FIXED_RATE' || body.triggerType === 'ONCE' ? body.triggerType : job.triggerType,
            cron: body.cron === undefined ? job.cron : (body.cron as string | null),
            fixedRate: body.fixedRate === undefined ? job.fixedRate : (body.fixedRate as number | null),
            onceFireAt: body.onceFireAt === undefined ? job.onceFireAt : (body.onceFireAt as string | null),
            param: body.param === undefined ? job.param : ((body.param && typeof body.param === 'object' ? body.param : null) as Record<string, unknown> | null),
            retryPolicy: body.retryPolicy === undefined ? job.retryPolicy : ((body.retryPolicy && typeof body.retryPolicy === 'object' ? body.retryPolicy : null) as JobAdminView['retryPolicy']),
            shardTotal: typeof body.shardTotal === 'number' && body.shardTotal >= 1 ? Math.floor(body.shardTotal) : job.shardTotal,
            timeoutMs: body.timeoutMs === undefined ? job.timeoutMs : (body.timeoutMs as number | null),
            misfirePolicy: body.misfirePolicy === undefined ? job.misfirePolicy : (body.misfirePolicy === 'IGNORE' ? 'IGNORE' : 'FIRE_ONCE_NOW'),
            version: String(Number(job.version) + 1),
          }
        : job,
    );
    const updated = store.jobs.find((job) => job.id === target.id)!;
    return ok(updated);
  }),
  http.post('/admin/v1/scheduler/jobs/:jobId/trigger', ({ params }) => {
    const target = store.jobs.find((job) => job.id === String(params.jobId));
    if (!target) {
      return fail('JOB_NOT_FOUND', '任务不存在', 404);
    }
    const executionId = genId();
    const execution: ExecutionView = {
      id: executionId,
      definitionId: target.id,
      jobKey: target.jobKey,
      triggerType: 'MANUAL',
      fireTime: nowIso(),
      state: 'PENDING',
      attempt: 0,
      shardIndex: null,
      result: null,
      error: null,
      costMs: null,
      traceId: null,
    };
    store.executions = [execution, ...store.executions];
    // 契约对齐后端：trigger 返回执行记录 id（wire string）
    return ok(executionId);
  }),
  http.delete('/admin/v1/scheduler/jobs/:jobId', ({ params }) => {
    const id = String(params.jobId);
    if (!store.jobs.some((job) => job.id === id)) {
      return fail('JOB_NOT_FOUND', '任务不存在', 404);
    }
    // 软删 mock 语义：移出清单（后端为 deleted 标记，行为面等价——列表不再返回）
    store.jobs = store.jobs.filter((job) => job.id !== id);
    return empty();
  }),
  http.get('/admin/v1/scheduler/executions', ({ request }) => {
    const params = urlOf(request).searchParams;
    const jobKey = params.get('jobKey');
    const state = params.get('state');
    const fireTimeFrom = params.get('fireTimeFrom');
    const fireTimeTo = params.get('fireTimeTo');
    const rows = store.executions.filter((row) => {
      if (jobKey && row.jobKey !== jobKey) return false;
      if (state && row.state !== state) return false;
      if (fireTimeFrom && row.fireTime < fireTimeFrom) return false;
      if (fireTimeTo && row.fireTime > fireTimeTo) return false;
      return true;
    });
    // fire_time 倒序（排序在后端语义）
    rows.sort((a, b) => b.fireTime.localeCompare(a.fireTime));
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.get('/admin/v1/scheduler/executors', () => ok(store.executors as ExecutorView[])),
];
