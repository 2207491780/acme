import { apiFetch } from '../http/client';
import type { PageResult } from './system';

/**
 * 基础设施域消费面（platform-infra；REQ-20261114-120101 前端收敛一期）：
 * 文件服务 /admin/v1/infra/files* 与 Excel demo 导入端点的类型与调用封装。
 * 下载（files/{id}/content）与导出（excel/demo-export|template）为二进制流，
 * 走 file-transfer 的 downloadFile（apiFetchRaw），不在本模块信封解析范围。
 */

/** 文件视图（契约对齐 FileView；id 序列化为字符串）。 */
export interface FileView {
  id: string;
  originalName: string;
  size: number;
  contentType: string;
  url: string | null;
  bizType: string | null;
  bizId: string | null;
  createdAt: string;
}

/** Excel 导入报告（契约对齐 ImportReport：total/valid/errors[{row,field,message}]）。 */
export interface ImportReportError {
  /** 行号（1 起的业务行号）。 */
  row: number;
  field: string | null;
  message: string;
}

export interface ImportReport {
  total: number;
  valid: number;
  errors: ImportReportError[];
}

/** 文件列表查询过滤（bizType/bizId 精确、keyword 按原名模糊；均缺省省略）。 */
export interface FileListFilters {
  bizType?: string;
  bizId?: string;
  keyword?: string;
}

/** Excel demo 端点（导出/导入范式；业务端点接入时按同范式传 URL）。 */
export const EXCEL_DEMO_ENDPOINTS = {
  template: '/admin/v1/infra/excel/demo-template',
  export: '/admin/v1/infra/excel/demo-export',
  import: '/admin/v1/infra/excel/demo-import',
} as const;

/** 直传准备视图（契约对齐 DirectUploadPreparationView，REQ-20261117-045754）。 */
export interface DirectUploadPreparationView {
  /** 目标对象键（files/{tenantCode}/yyyy/MM/dd/name；租户段服务端组装，客户端不可传）。 */
  objectKey: string;
  /** PUT 直传地址：LOCAL=同源票据端点（带 Authorization）；MINIO/OSS=预签名绝对 URL（不带 Authorization）。 */
  uploadUrl: string;
  /** 直传 HTTP 方法（契约固定 PUT）。 */
  method: string;
  /** PUT 须携带的头（含签名所需的 Content-Type 等）。 */
  headers: Record<string, string>;
  /** 准备结果有效期（秒）。 */
  expiresInSeconds: number;
  /** 服务端一次性票据（仅 LOCAL 模式返回；MINIO/OSS 为 null）。 */
  ticket: string | null;
}

/** 直传准备入参（校验链=空/大小/白名单/配额；图片语义在 completion 校验真实字节）。 */
export interface DirectUploadPreparePayload {
  fileName: string;
  contentType: string;
  size: number;
  bizType?: string;
  bizId?: string;
}

/** 直传完成入参（校验=租户归属+exists+size 一致+图片语义；幂等：同 objectKey 未删行直接返回既有 FileView）。 */
export interface DirectUploadCompletePayload {
  objectKey: string;
  fileName: string;
  contentType: string;
  size: number;
  bizType?: string;
  bizId?: string;
}

/** 文件服务端点（上传/列表/删除走信封 JSON；内容下载走流；直传三段 REQ-20261117-045754）。 */
export const FILE_ENDPOINTS = {
  base: '/admin/v1/infra/files',
  content: (fileId: string): string => `/admin/v1/infra/files/${fileId}/content`,
  /** 直传准备（返回 DirectUploadPreparationView）。 */
  directUploadPreparations: '/admin/v1/infra/files/direct-upload-preparations',
  /** 直传完成（返回 FileView，幂等）。 */
  directUploadCompletions: '/admin/v1/infra/files/direct-upload-completions',
  /** LOCAL 模式票据直传端点（原始字节 PUT、认证面；MINIO/OSS 预签名模式无此路径）。 */
  directUploadTicket: (ticket: string): string =>
    `/admin/v1/infra/files/direct-uploads/${encodeURIComponent(ticket)}`,
} as const;

/** 通用 multipart 上传（FormData；Content-Type 由浏览器按 boundary 自动携带）。 */
export async function uploadFormData<T>(
  url: string,
  file: File,
  meta: { bizType?: string; bizId?: string } = {},
): Promise<T> {
  const form = new FormData();
  form.append('file', file);
  if (meta.bizType) form.append('bizType', meta.bizType);
  if (meta.bizId) form.append('bizId', meta.bizId);
  return apiFetch<T>(url, { method: 'POST', body: form });
}

/** 基础设施域接口（platform-infra）。 */
export const infraApi = {
  /** 文件分页（bizType/bizId 精确过滤、keyword 按原名模糊；权限点 infra:file:list；signal 为可选取消信号）。 */
  fileList: (page: number, size: number, filters: FileListFilters = {}, signal?: AbortSignal): Promise<PageResult<FileView>> => {
    const qs = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.bizType) qs.set('bizType', filters.bizType);
    if (filters.bizId) qs.set('bizId', filters.bizId);
    if (filters.keyword) qs.set('keyword', filters.keyword);
    return apiFetch<PageResult<FileView>>(`${FILE_ENDPOINTS.base}?${qs.toString()}`, { signal });
  },
  /** 上传文件（multipart file + 可选 bizType/bizId；权限点 infra:file:upload）。
   *  超限/类型拒绝由后端以 FILE_SIZE_EXCEEDED/FILE_TYPE_REJECTED 拒绝（前端经 transferErrorMessage 转译）。 */
  fileUpload: (file: File, meta: { bizType?: string; bizId?: string } = {}): Promise<FileView> =>
    uploadFormData<FileView>(FILE_ENDPOINTS.base, file, meta),
  /** 直传准备（REQ-20261117-045754；权限点 infra:file:upload）：校验+生成带租户段 objectKey，
   *  返回 LOCAL 票据端点或 MINIO/OSS 预签名 PUT 地址。配额超限以 STORAGE_QUOTA_EXCEEDED（413 语义）拒绝。 */
  fileDirectUploadPrepare: (payload: DirectUploadPreparePayload): Promise<DirectUploadPreparationView> =>
    apiFetch<DirectUploadPreparationView>(FILE_ENDPOINTS.directUploadPreparations, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 直传完成（REQ-20261117-045754；权限点 infra:file:upload）：对象存在+大小一致+图片语义校验，
   *  幂等落库返回 FileView。失败码经 uploadErrorMessage 转译（QUOTA/IMAGE/TICKET/SIZE_MISMATCH/MISSING 族）。 */
  fileDirectUploadComplete: (payload: DirectUploadCompletePayload): Promise<FileView> =>
    apiFetch<FileView>(FILE_ENDPOINTS.directUploadCompletions, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除文件（软删 + 对象移除；幂等：已删/对象不存在均成功；权限点 infra:file:delete）。 */
  fileDelete: (fileId: string): Promise<void> =>
    apiFetch<void>(`${FILE_ENDPOINTS.base}/${fileId}`, { method: 'DELETE' }),
};
