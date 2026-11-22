import { apiFetch, apiJson, ApiError, buildQuery } from '../http/client';
import type { PageResult } from './system';

/**
 * 工作流域消费面（platform-workflow；REQ-20261122-023441 契约 API 面）：
 * 流程定义 6 端点的类型与调用封装。wire 口径：id/version 为字符串；列表 bpmnXml 置空。
 *
 * 错误码（FR 对齐后端）：01-0001 KEY_DUPLICATE(409) / 01-0002 XML_INVALID(400) /
 * 01-0003 VALIDATION_FAILED(409，明细随 data) / 01-0004 NOT_FOUND(404/语义) / 01-0005 DISABLED(409)。
 * saveXml/deploy 校验失败 = Result.fail + data 明细（区别于裸 View 成功信封）——
 * ApiError.data 已携带明细，经 workflowValidationErrorsOf 提取。
 */

/** 流程定义视图（契约对齐 ProcessDefinitionView；id/version wire 字符串；列表 bpmnXml 置空）。 */
export interface ProcessDefinitionView {
  id: string;
  key: string;
  name: string;
  version: string;
  status: 'DRAFT' | 'DEPLOYED' | 'DISABLED';
  bpmnXml: string | null;
  layoutJson: string | null;
  createdAt: string;
  deployedAt: string | null;
}

/** 新建草稿载荷（契约 Create：key @NotBlank 64 / name 128）。 */
export interface ProcessDefinitionCreatePayload {
  key: string;
  name: string;
}

/** 保存 XML 载荷（契约 XmlSave：bpmnXml @NotBlank；仅草稿态可保存；
 *  确认⑤：可选 layoutJson 与 bpmnXml 同事务落库 layout_json 列）。 */
export interface ProcessDefinitionXmlSavePayload {
  bpmnXml: string;
  layoutJson?: string;
}

/** 流程定义列表过滤（keyword 按 key/name 模糊；status 精确；均缺省省略）。 */
export interface ProcessDefinitionListFilters {
  keyword?: string;
  status?: 'DRAFT' | 'DEPLOYED' | 'DISABLED';
}

/** 后端 VALIDATION_FAILED 明细项（nodeId 可空=模型级问题；field 预留字段级定位）。 */
export interface WorkflowValidationErrorDto {
  nodeId?: string | null;
  field?: string | null;
  message: string;
}

/** 工作流域错误码（01-xxxx 族；字面量以契约为准）。 */
export const WORKFLOW_ERROR_CODES = {
  KEY_DUPLICATE: '01-0001',
  XML_INVALID: '01-0002',
  VALIDATION_FAILED: '01-0003',
  NOT_FOUND: '01-0004',
  DISABLED: '01-0005',
} as const;

/**
 * 从错误中提取 VALIDATION_FAILED 明细（校验面板渲染面）：
 * 非 ApiError / code 不符 / data 形状不合法 → 空数组（明细缺失时页面回退 message 展示）。
 */
export function workflowValidationErrorsOf(error: unknown): WorkflowValidationErrorDto[] {
  if (!(error instanceof ApiError) || error.code !== WORKFLOW_ERROR_CODES.VALIDATION_FAILED) {
    return [];
  }
  const data: unknown = error.data;
  if (!Array.isArray(data)) {
    return [];
  }
  const items: WorkflowValidationErrorDto[] = [];
  for (const raw of data) {
    if (typeof raw !== 'object' || raw === null) continue;
    const candidate = raw as Record<string, unknown>;
    if (typeof candidate.message !== 'string' || candidate.message === '') continue;
    items.push({
      nodeId: typeof candidate.nodeId === 'string' && candidate.nodeId !== '' ? candidate.nodeId : null,
      field: typeof candidate.field === 'string' && candidate.field !== '' ? candidate.field : null,
      message: candidate.message,
    });
  }
  return items;
}

/** 流程定义端点（base 下挂 xml/deploy/disable 动作；GET 详情与列表同 base）。 */
export const WORKFLOW_ENDPOINTS = {
  base: '/admin/v1/workflow/process-definitions',
  xml: (definitionId: string): string =>
    `/admin/v1/workflow/process-definitions/${encodeURIComponent(definitionId)}/xml`,
  deploy: (definitionId: string): string =>
    `/admin/v1/workflow/process-definitions/${encodeURIComponent(definitionId)}/deploy`,
  disable: (definitionId: string): string =>
    `/admin/v1/workflow/process-definitions/${encodeURIComponent(definitionId)}/disable`,
} as const;

// ————————————————————————————————————————————————————————————————————————————
// 实例/任务域（REQ-20261122-102730 BPM-3 契约⑤⑧）：instanceApi/taskApi + 5 类型。
// wire 口径：id 一律字符串（定义域先例延续）；实例列表/详情/待办任务行均带投影
// definitionKey/definitionName/businessKey/initiator（后端 JOIN 装配，前端禁 N+1 二次取详情）。
// ————————————————————————————————————————————————————————————————————————————

/** 流程实例状态（状态机：RUNNING/SUSPENDED(运维挂起|SERVICE_TASK_FAILED|NO_ASSIGNEE_RESOLVED)/COMPLETED/TERMINATED）。 */
export type ProcessInstanceStatus = 'RUNNING' | 'SUSPENDED' | 'COMPLETED' | 'TERMINATED';

/** 任务状态（状态机：CREATED→CLAIMED→COMPLETED|REJECTED|CANCELLED；固定 assignee 亦先 CREATED）。 */
export type WorkflowTaskStatus = 'CREATED' | 'CLAIMED' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';

/** 历史动作（ENTER/LEAVE 配对可算停留时长；REJECT_EDGE=驳回回流边；TIMER_FIRE=定时触发）。 */
export type HistoryAction = 'ENTER' | 'LEAVE' | 'REJECT_EDGE' | 'TIMER_FIRE';

/** 活跃节点视图（实例详情 readOnly 流程图 running 态高亮定位面）。 */
export interface ActiveNodeView {
  nodeId: string;
  nodeName?: string | null;
  nodeType?: string | null;
  /** 进入时刻（ISO；缺省=无时间戳）。 */
  enterAt?: string | null;
}

/** 任务项视图（实例详情内嵌任务面；与 TaskView 共用基础字段，无跨实例投影）。 */
export interface TaskItemView {
  id: string;
  instanceId: string;
  nodeId: string;
  nodeName: string | null;
  assignee: string | null;
  status: WorkflowTaskStatus;
  claimTime: string | null;
  completeTime: string | null;
  createTime: string;
}

/** 任务视图（todo/done 列表行；REQ-20261122-102730 契约⑤投影扩展——
 *  definitionKey/definitionName/businessKey/initiator 由后端 JOIN definition+instance 装配）。 */
export interface TaskView extends TaskItemView {
  definitionKey: string | null;
  definitionName: string | null;
  businessKey: string | null;
  initiator: string | null;
}

/** 流程实例视图（契约对齐 ProcessInstanceView；definitionName 为契约⑤投影扩展——
 *  initiated 列表回显，其余场景可空）。 */
export interface ProcessInstanceView {
  id: string;
  definitionId: string;
  definitionKey: string | null;
  /** 契约⑤投影扩展（JOIN definition；initiated 列表与实例行回显）。 */
  definitionName?: string | null;
  businessKey: string | null;
  initiator: string | null;
  status: ProcessInstanceStatus;
  suspendReason: string | null;
  startTime: string;
  endTime: string | null;
  /** 流程变量摘要（详情回显；列表可置空）。 */
  variables: Record<string, unknown> | null;
  /** 活跃节点（详情回显；列表可置空数组）。 */
  activeNodes: ActiveNodeView[];
  /** 活动任务（详情回显；列表可置空数组）。 */
  tasks: TaskItemView[];
}

/** 历史活动视图（非分页 List；ENTER/LEAVE 配对，REJECT_EDGE 的 nodeId=sequenceFlow id 供边标红）。 */
export interface HistoryActivityView {
  id: string;
  nodeId: string;
  nodeType: string | null;
  action: HistoryAction;
  assignee: string | null;
  startAt: string | null;
  endAt: string | null;
  detail: string | null;
}

/** 发起实例载荷（Create：definitionId 必填；业务键/变量可选）。 */
export interface ProcessInstanceStartPayload {
  definitionId: string;
  businessKey?: string | null;
  variables?: Record<string, unknown> | null;
}

/** 实例分页过滤（definitionKey/status/businessKey 精确；undefined/null/空串均省略）。 */
export interface ProcessInstanceListFilters {
  definitionKey?: string | null;
  status?: ProcessInstanceStatus | null;
  businessKey?: string | null;
}

/** 任务分页过滤（契约⑤：todo/done 增可选 definitionKey/status；均缺省省略）。 */
export interface TaskListFilters {
  definitionKey?: string;
  status?: WorkflowTaskStatus;
}

/** 办理任务载荷（REQ-20261122-150750 契约⑦形状修正：后端 TaskCompleteRequest{form} wire 同形——
 *  form=自由键值对象（落 wf_task.form_data jsonb），comment 独立；均为可选。
 *  修正现网缺口：旧形状仅 {comment?} 且顶层直发被后端丢弃（form=null），本形状补齐 form 通道）。 */
export interface TaskCompletePayload {
  form?: Record<string, unknown>;
  comment?: string;
}

/** 驳回载荷（targetNodeId 可选=驳回到指定节点；comment 可选）。 */
export interface TaskRejectPayload {
  targetNodeId?: string | null;
  comment?: string | null;
}

/** 转办/委托载荷（delegate 委托链单层）。 */
export interface TaskAssigneePayload {
  assignee: string;
}

/** 实例域端点（base 下挂 detail/history/terminate/suspend/resume）。 */
export const WORKFLOW_INSTANCE_ENDPOINTS = {
  base: '/admin/v1/workflow/process-instances',
  initiated: '/admin/v1/workflow/process-instances/initiated',
  detail: (instanceId: string): string =>
    `/admin/v1/workflow/process-instances/${encodeURIComponent(instanceId)}`,
  history: (instanceId: string): string =>
    `/admin/v1/workflow/process-instances/${encodeURIComponent(instanceId)}/history`,
  terminate: (instanceId: string): string =>
    `/admin/v1/workflow/process-instances/${encodeURIComponent(instanceId)}/terminate`,
  suspend: (instanceId: string): string =>
    `/admin/v1/workflow/process-instances/${encodeURIComponent(instanceId)}/suspend`,
  resume: (instanceId: string): string =>
    `/admin/v1/workflow/process-instances/${encodeURIComponent(instanceId)}/resume`,
} as const;

/** 任务域端点（base 下挂 todo/done 与 complete/reject/claim/unclaim/transfer/delegate 动作）。 */
export const WORKFLOW_TASK_ENDPOINTS = {
  base: '/admin/v1/workflow/tasks',
  todo: '/admin/v1/workflow/tasks/todo',
  done: '/admin/v1/workflow/tasks/done',
  complete: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/complete`,
  reject: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/reject`,
  claim: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/claim`,
  unclaim: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/unclaim`,
  transfer: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/transfer`,
  delegate: (taskId: string): string =>
    `/admin/v1/workflow/tasks/${encodeURIComponent(taskId)}/delegate`,
} as const;

/** 实例域接口（platform-workflow；管理面 workflow:instance:manage(80) / 读面 process-instances(80)）。 */
export const instanceApi = {
  /** 实例分页（definitionKey/status/businessKey 过滤；权限点 workflow:instance:manage）。 */
  page: (
    page: number,
    size: number,
    filters: ProcessInstanceListFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<ProcessInstanceView>> =>
    apiFetch<PageResult<ProcessInstanceView>>(
      `${WORKFLOW_INSTANCE_ENDPOINTS.base}?${buildQuery({
        page, size, definitionKey: filters.definitionKey, status: filters.status, businessKey: filters.businessKey,
      })}`,
      { signal },
    ),

  /** 我发起的实例分页（待办中心「我发起」子区；投影 definitionName 随行回显）。 */
  initiated: (page: number, size: number, signal?: AbortSignal): Promise<PageResult<ProcessInstanceView>> =>
    apiFetch<PageResult<ProcessInstanceView>>(
      `${WORKFLOW_INSTANCE_ENDPOINTS.initiated}?${buildQuery({ page, size })}`,
      { signal },
    ),

  /** 实例详情（含 variables/activeNodes/tasks 全量回显）。 */
  get: (instanceId: string, signal?: AbortSignal): Promise<ProcessInstanceView> =>
    apiFetch<ProcessInstanceView>(WORKFLOW_INSTANCE_ENDPOINTS.detail(instanceId), { signal }),

  /** 历史活动（非分页 List；ENTER/LEAVE 配对 + REJECT_EDGE 边回流）。 */
  history: (instanceId: string, signal?: AbortSignal): Promise<HistoryActivityView[]> =>
    apiFetch<HistoryActivityView[]>(WORKFLOW_INSTANCE_ENDPOINTS.history(instanceId), { signal }),

  /** 发起实例（definition 须 DEPLOYED，否则 DISABLED 409）。 */
  start: (payload: ProcessInstanceStartPayload): Promise<ProcessInstanceView> =>
    apiJson<ProcessInstanceView>(WORKFLOW_INSTANCE_ENDPOINTS.base, 'POST', payload),

  /** 终止（RUNNING→TERMINATED；void 端点 emptyAsSuccess 双口径）。 */
  terminate: (instanceId: string): Promise<void> =>
    apiFetch<void>(WORKFLOW_INSTANCE_ENDPOINTS.terminate(instanceId), { method: 'POST' }, { emptyAsSuccess: true }),

  /** 挂起（RUNNING→SUSPENDED，reason 落 suspendReason；void 端点信封/空体双口径）。 */
  suspend: (instanceId: string, reason: string | null): Promise<void> =>
    apiJson<void>(WORKFLOW_INSTANCE_ENDPOINTS.suspend(instanceId), 'POST', { reason }, { emptyAsSuccess: true }),

  /** 恢复（SUSPENDED→RUNNING，suspendReason 清空）。 */
  resume: (instanceId: string): Promise<void> =>
    apiFetch<void>(WORKFLOW_INSTANCE_ENDPOINTS.resume(instanceId), { method: 'POST' }, { emptyAsSuccess: true }),
};

/** 任务域接口（读面 workflow:task:list(81) / 写面 workflow:task:operate(82)）。 */
export const taskApi = {
  /** 我的待办分页（契约⑤可选过滤 definitionKey/status）。 */
  todo: (
    page: number,
    size: number,
    filters: TaskListFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<TaskView>> =>
    apiFetch<PageResult<TaskView>>(
      `${WORKFLOW_TASK_ENDPOINTS.todo}?${buildQuery({ page, size, definitionKey: filters.definitionKey, status: filters.status })}`,
      { signal },
    ),

  /** 我已办分页（同 todo 过滤口径）。 */
  done: (
    page: number,
    size: number,
    filters: TaskListFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<TaskView>> =>
    apiFetch<PageResult<TaskView>>(
      `${WORKFLOW_TASK_ENDPOINTS.done}?${buildQuery({ page, size, definitionKey: filters.definitionKey, status: filters.status })}`,
      { signal },
    ),

  /** 办理任务（REQ-20261122-150750 契约⑦：顶层 {form:{...},comment} 直发；候选 ALL 策略 CREATED 可直办）。 */
  complete: (taskId: string, payload: TaskCompletePayload = {}): Promise<void> =>
    apiFetch<void>(WORKFLOW_TASK_ENDPOINTS.complete(taskId), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }, { emptyAsSuccess: true }),

  /** 驳回（targetNodeId 可选=驳回到指定节点）。 */
  reject: (taskId: string, payload: TaskRejectPayload = {}): Promise<void> =>
    apiFetch<void>(WORKFLOW_TASK_ENDPOINTS.reject(taskId), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }, { emptyAsSuccess: true }),

  /** 认领（CREATED→CLAIMED，assignee=本人）。 */
  claim: (taskId: string): Promise<void> =>
    apiFetch<void>(WORKFLOW_TASK_ENDPOINTS.claim(taskId), { method: 'POST' }, { emptyAsSuccess: true }),

  /** 取消认领（CLAIMED→CREATED，assignee 清空）。 */
  unclaim: (taskId: string): Promise<void> =>
    apiFetch<void>(WORKFLOW_TASK_ENDPOINTS.unclaim(taskId), { method: 'POST' }, { emptyAsSuccess: true }),

  /** 转办（assignee 变更；void 端点信封/空体双口径）。 */
  transfer: (taskId: string, payload: TaskAssigneePayload): Promise<void> =>
    apiJson<void>(WORKFLOW_TASK_ENDPOINTS.transfer(taskId), 'POST', payload, { emptyAsSuccess: true }),

  /** 委托（委托链单层；void 端点信封/空体双口径）。 */
  delegate: (taskId: string, payload: TaskAssigneePayload): Promise<void> =>
    apiJson<void>(WORKFLOW_TASK_ENDPOINTS.delegate(taskId), 'POST', payload, { emptyAsSuccess: true }),
};

/** 工作流域接口（platform-workflow；写面 workflow:definition:deploy(79) / 读面 list(78)）。 */
export const workflowApi = {
  /** 流程定义分页（keyword 模糊、status 精确；权限点 workflow:definition:list；signal 可选取消）。 */
  list: (
    page: number,
    size: number,
    filters: ProcessDefinitionListFilters = {},
    signal?: AbortSignal,
  ): Promise<PageResult<ProcessDefinitionView>> =>
    apiFetch<PageResult<ProcessDefinitionView>>(
      `${WORKFLOW_ENDPOINTS.base}?${buildQuery({ page, size, keyword: filters.keyword, status: filters.status })}`,
      { signal },
    ),

  /** 流程定义详情（含 bpmnXml/layoutJson 回显；设计器打开与版本对比取数面）。 */
  get: (definitionId: string, signal?: AbortSignal): Promise<ProcessDefinitionView> =>
    apiFetch<ProcessDefinitionView>(`${WORKFLOW_ENDPOINTS.base}/${encodeURIComponent(definitionId)}`, { signal }),

  /** 新建草稿（key/name；返回草稿视图，前端随后跳转设计器）。 */
  create: (payload: ProcessDefinitionCreatePayload): Promise<ProcessDefinitionView> =>
    apiJson<ProcessDefinitionView>(WORKFLOW_ENDPOINTS.base, 'POST', payload),

  /** 保存 XML+布局（单请求；仅草稿态——已部署定义保存被后端拒）。 */
  saveXml: (definitionId: string, payload: ProcessDefinitionXmlSavePayload): Promise<ProcessDefinitionView> =>
    apiJson<ProcessDefinitionView>(WORKFLOW_ENDPOINTS.xml(definitionId), 'PUT', payload),

  /** 部署（校验失败 409 VALIDATION_FAILED 明细随 data；成功返回 DEPLOYED 视图）。 */
  deploy: (definitionId: string): Promise<ProcessDefinitionView> =>
    apiFetch<ProcessDefinitionView>(WORKFLOW_ENDPOINTS.deploy(definitionId), { method: 'POST' }),

  /** 停用（已部署定义下线；返回 DISABLED 视图）。 */
  disable: (definitionId: string): Promise<ProcessDefinitionView> =>
    apiFetch<ProcessDefinitionView>(WORKFLOW_ENDPOINTS.disable(definitionId), { method: 'POST' }),
};
