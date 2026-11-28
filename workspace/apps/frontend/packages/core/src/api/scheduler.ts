import { apiFetch, apiJson, buildQuery } from '../http/client';
import type { PageResult } from './system';

/**
 * 调度中心域消费面（platform-scheduler；REQ-20261122-023213 JOB-3 前端切新 API）：
 * /admin/v1/scheduler/** 八端点（任务定义 CRUD + 启停/触发/删除 + 执行历史 + 执行器监控）。
 * DTO 形状以契约背景节为准：JobView 17 字段（id/version wire 字符串化）；
 * ExecutionView 12 字段；执行器视图对齐 sch_executor_registry。
 */

/** 任务触发类型（sch_job_definition.trigger_type：CRON|FIXED_RATE|ONCE）。 */
export type JobTriggerType = 'CRON' | 'FIXED_RATE' | 'ONCE';

/** Misfire 策略（FIRE_ONCE_NOW=错峰即刻补跑；IGNORE=忽略）。 */
export type JobMisfirePolicy = 'FIRE_ONCE_NOW' | 'IGNORE';

/** 任务定义状态（启用=算初始排期回填时间轮；停用=removeWheel）。 */
export type JobStatus = 'ENABLED' | 'DISABLED';

/** 重试策略（sch_job_definition.retry_policy jsonb {maxAttempts,initialIntervalMs}）。 */
export interface JobRetryPolicy {
  maxAttempts: number;
  initialIntervalMs: number;
}

/**
 * 任务定义管理视图（契约对齐后端 JobView，17 字段；REQ-20261127-234158 wire golden 对齐：
 * 后端序列化为权威——视图键 cronExpr/fixedRateMs；LongToStringModule 全局生效，
 * id/version/fixedRateMs/timeoutMs 视图 wire 均为 JSON 字符串，shardTotal（Integer）为数值，
 * cronExpr 为字符串或 null）。
 * 字段集=JOB-1 契约 §③ sch_job_definition 列面（id/job_key/app_name/handler/trigger_type/
 * cron_expr/fixed_rate_ms/once_fire_at/param/retry_policy/shard_total/timeout_ms/misfire_policy/
 * status/next_fire_time/审计列/version）。列表列取精简集（契约②）：
 * jobKey/appName/triggerType/cronExpr 或 fixedRateMs/nextFireTime/status/timeoutMs/shardTotal。
 */
export interface JobAdminView {
  id: string;
  /** 任务键（创建后不可改；重复 → KEY_DUPLICATE）。 */
  jobKey: string;
  /** 目标应用（spring.application.name，如 platform-infra）。 */
  appName: string;
  /** 处理器标识（@JobHandler 注册的 handler）。 */
  handler: string | null;
  triggerType: JobTriggerType;
  /** Cron 表达式（仅 CRON；wire 键 cronExpr，短名 cron 不映射）。 */
  cronExpr: string | null;
  /** 固定频率毫秒（仅 FIXED_RATE；wire 键 fixedRateMs，LongToStringModule 字符串化）。 */
  fixedRateMs: string | null;
  /** 一次性触发时间（仅 ONCE，ISO-8601）。 */
  onceFireAt: string | null;
  /** 任务参数（jsonb，透传下发指令）。 */
  param: Record<string, unknown> | null;
  retryPolicy: JobRetryPolicy | null;
  shardTotal: number;
  /** 执行超时毫秒（RUNNING 超时巡检 → TIMEOUT；wire 字符串化）。 */
  timeoutMs: string | null;
  misfirePolicy: JobMisfirePolicy;
  status: JobStatus;
  /** 下次触发时间（ISO-8601；停用=null，启用=算初始排期回填）。 */
  nextFireTime: string | null;
  createdAt: string;
  /** 乐观锁版本（wire 字符串，编辑随体回传）。 */
  version: string;
}

/** 执行状态（sch_job_execution.state）。 */
export type ExecutionState =
  | 'PENDING'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'TIMEOUT'
  | 'CANCELLED';

/** 执行历史视图（契约对齐 ExecutionView 12 字段；id/definitionId wire 字符串化；
 *  分页按 fire_time 倒序，排序在后端）。 */
export interface ExecutionView {
  id: string;
  definitionId: string;
  jobKey: string;
  /** 触发方式（SCHEDULE|MANUAL|RETRY|EVENT）。 */
  triggerType: string;
  fireTime: string;
  state: ExecutionState;
  /** 尝试次数（0=首发）。 */
  attempt: number;
  /** 分片序号（非分片任务=null）。 */
  shardIndex: number | null;
  /** 执行结果（jsonb，透传）。 */
  result: Record<string, unknown> | null;
  error: string | null;
  /** 耗时毫秒。 */
  costMs: number | null;
  traceId: string | null;
}

/** 执行器视图（契约对齐 sch_executor_registry：appName+instanceId 唯一；心跳 stale 30s→OFFLINE）。 */
export interface ExecutorView {
  appName: string;
  instanceId: string;
  host: string;
  port: number;
  /** 注册的任务键清单（@JobHandler handlers）。 */
  handlers: string[];
  heartbeatAt: string | null;
  status: 'ONLINE' | 'OFFLINE';
}

/** 任务分页过滤（契约②：jobKey/appName/status；均缺省省略）。 */
export interface JobPageFilters {
  jobKey?: string;
  appName?: string;
  status?: JobStatus;
}

/** 任务创建/编辑载荷（job_key 创建后不可改；编辑不带 jobKey 并随体携带 version）。
 *  REQ-20261127-234158 wire 对齐：载荷键为 cronExpr/fixedRateMs（短名 cron/fixedRate 后端不映射）；
 *  载荷发 number 合法（后端反序列化接受 number 与 string 双口径），FE 保持发 number。 */
export interface JobSavePayload {
  jobKey: string;
  appName: string;
  handler?: string | null;
  triggerType: JobTriggerType;
  cronExpr?: string | null;
  fixedRateMs?: number | null;
  onceFireAt?: string | null;
  param?: Record<string, unknown> | null;
  retryPolicy?: JobRetryPolicy | null;
  shardTotal?: number;
  timeoutMs?: number | null;
  misfirePolicy?: JobMisfirePolicy;
}

/** 任务编辑载荷（乐观锁 version 必带；jobKey 同值回传——修改即 KEY_IMMUTABLE，由后端拒绝）。 */
export interface JobUpdatePayload extends JobSavePayload {
  version: string;
}

/** 执行历史过滤（jobKey/state 精确 + fireTime 窗口 [from,to]；均缺省省略）。 */
export interface ExecutionPageFilters {
  jobKey?: string;
  state?: ExecutionState;
  fireTimeFrom?: string;
  fireTimeTo?: string;
}

/** 调度中心端点（八端点；页面权限 73~77 已种子，75 以 PUT /jobs/** 通配覆盖 status/trigger）。 */
export const SCHEDULER_ENDPOINTS = {
  jobs: '/admin/v1/scheduler/jobs',
  jobById: (jobId: string): string => `/admin/v1/scheduler/jobs/${encodeURIComponent(jobId)}`,
  jobStatus: (jobId: string): string => `/admin/v1/scheduler/jobs/${encodeURIComponent(jobId)}/status`,
  jobTrigger: (jobId: string): string => `/admin/v1/scheduler/jobs/${encodeURIComponent(jobId)}/trigger`,
  executions: '/admin/v1/scheduler/executions',
  executors: '/admin/v1/scheduler/executors',
} as const;

/** 调度中心域接口（platform-scheduler；列表/历史 73，启停/触发/新建/编辑 75（契约⑤），删除 76）。 */
export const schedulerApi = {
  /** 任务定义分页（过滤 jobKey/appName/status；signal 为可选取消信号）。 */
  jobPage: (
    page: number,
    size: number,
    filters: JobPageFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<JobAdminView>> =>
    apiFetch<PageResult<JobAdminView>>(
      `${SCHEDULER_ENDPOINTS.jobs}?${buildQuery({ page, size, jobKey: filters.jobKey, appName: filters.appName, status: filters.status })}`,
      { signal },
    ),
  /** 新建任务（默认 DISABLED；jobKey 重复 → KEY_DUPLICATE）。 */
  jobCreate: (payload: JobSavePayload): Promise<JobAdminView> =>
    apiJson<JobAdminView>(SCHEDULER_ENDPOINTS.jobs, 'POST', payload),
  /** 编辑任务（version 乐观锁；jobKey 不可改 → KEY_IMMUTABLE）。 */
  jobUpdate: (jobId: string, payload: JobUpdatePayload): Promise<JobAdminView> =>
    apiJson<JobAdminView>(SCHEDULER_ENDPOINTS.jobById(jobId), 'PUT', payload),
  /** 启停任务（专用端点；启用=算初始排期回填时间轮，停用=removeWheel）。 */
  jobUpdateStatus: (jobId: string, status: JobStatus): Promise<JobAdminView> =>
    apiJson<JobAdminView>(SCHEDULER_ENDPOINTS.jobStatus(jobId), 'PUT', { status }),
  /**
   * 手动触发（MANUAL execution；返回执行记录 id，wire 字符串）。
   * 兼容 id 直接串与 { id | executionId } 对象载荷（后端/admin 面两种同形口径）。
   */
  jobTrigger: async (jobId: string): Promise<string> => {
    const data = await apiJson<string | { id?: string; executionId?: string } | null>(
      SCHEDULER_ENDPOINTS.jobTrigger(jobId),
      'POST',
      {},
    );
    if (typeof data === 'string') return data;
    return String(data?.id ?? data?.executionId ?? '');
  },
  /** 删除任务（软删）。void 端点：emptyAsSuccess 兼容空体 2xx 与 Result 信封双口径。 */
  jobDelete: (jobId: string): Promise<void> =>
    apiFetch<void>(SCHEDULER_ENDPOINTS.jobById(jobId), { method: 'DELETE' }, { emptyAsSuccess: true }),
  /** 执行历史分页（jobKey/state/fireTime 窗口过滤；后端按 fire_time 倒序）。 */
  executionPage: (
    page: number,
    size: number,
    filters: ExecutionPageFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<ExecutionView>> =>
    apiFetch<PageResult<ExecutionView>>(
      `${SCHEDULER_ENDPOINTS.executions}?${buildQuery({ page, size, jobKey: filters.jobKey, state: filters.state, fireTimeFrom: filters.fireTimeFrom, fireTimeTo: filters.fireTimeTo })}`,
      { signal },
    ),
  /** 执行器清单（含在线状态；权限归 73 job:list）。 */
  executorList: (signal?: AbortSignal): Promise<ExecutorView[]> =>
    apiFetch<ExecutorView[]>(SCHEDULER_ENDPOINTS.executors, { signal }),
};
