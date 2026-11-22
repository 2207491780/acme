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
