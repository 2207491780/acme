import { ApiError, apiFetchRaw, rawPut } from './http/client';
import type { ApiEnvelope } from './http/client';
import { i18n } from './i18n';
import { FILE_ENDPOINTS, infraApi, uploadFormData } from './api/infra';
import type { DirectUploadPreparationView, FileView } from './api/infra';

/**
 * 上传/下载工具（REQ-20261114-120101 前端收敛一期；REQ-20261117-045754 直传二期）：
 * - 下载走 apiFetchRaw（鉴权 + 单次续约同源），按 Content-Disposition 还原原文件名；
 * - 上传主链路走 uploadDirect（preparation → PUT 原始字节 → completion），FormData 上传保留
 *   为兜底（uploadFormData / infraApi.fileUpload）与业务端点通路；
 * - 既有错误码 FILE_SIZE_EXCEEDED / FILE_TYPE_REJECTED / EXCEL_PARSE_FAILED / FILE_NOT_FOUND
 *   统一经 transferErrorMessage 转译；直传新增错误码经 uploadErrorMessage（i18n，随语言切换）转译。
 */

/** 传输域错误码 → 用户可读文案（命中即转译，未命中回退后端 message）。 */
export const TRANSFER_ERROR_MESSAGES: Record<string, string> = {
  FILE_SIZE_EXCEEDED: '文件大小超出限制',
  FILE_TYPE_REJECTED: '文件类型不被允许',
  EXCEL_PARSE_FAILED: 'Excel 文件解析失败，请确认文件为合法的 xlsx 格式',
  FILE_NOT_FOUND: '文件不存在或已被删除',
};

/** 直传域错误码 → i18n key（files.errors.*；沿用 system-errors.ts 的 i18n 映射模式，随语言切换）。
 *  码名同时收录 STORAGE_ 前缀与裸族码两种口径（后端 StorageError 族码命名以契约为准）。 */
export const DIRECT_UPLOAD_ERROR_I18N_KEYS: Record<string, string> = {
  STORAGE_QUOTA_EXCEEDED: 'files.errors.quotaExceeded',
  QUOTA_EXCEEDED: 'files.errors.quotaExceeded',
  STORAGE_IMAGE_INVALID: 'files.errors.imageInvalid',
  IMAGE_INVALID: 'files.errors.imageInvalid',
  STORAGE_TICKET_INVALID: 'files.errors.ticketInvalid',
  TICKET_INVALID: 'files.errors.ticketInvalid',
  SIZE_MISMATCH: 'files.errors.sizeMismatch',
  STORAGE_OBJECT_MISSING: 'files.errors.objectMissing',
  PRESIGN_UNSUPPORTED: 'files.errors.presignUnsupported',
};

/** 传输/导入失败的用户可读文案：命中已知错误码 → 转译；尾部附错误码便于定位（AC-4/AC-6 转译提示口径）；
 *  未命中已知码 → 透出后端 message（同样附码）或兜底文案。 */
export function transferErrorMessage(ex: unknown, fallback = '操作失败，请稍后重试'): string {
  if (ex instanceof ApiError) {
    const base = TRANSFER_ERROR_MESSAGES[ex.code] ?? (ex.message || fallback);
    return `${base}（${ex.code}）`;
  }
  if (ex instanceof Error && ex.message) {
    return ex.message;
  }
  return fallback;
}

/** 上传失败的用户可读文案（REQ-20261117-045754）：直传新增错误码命中 i18n key → 随语言切换转译
 *  （口径对齐 system-errors，尾部附码）；其余（既有码/未知码/非 ApiError）回退 transferErrorMessage。 */
export function uploadErrorMessage(ex: unknown, fallback: string): string {
  if (ex instanceof ApiError) {
    const key = DIRECT_UPLOAD_ERROR_I18N_KEYS[ex.code];
    if (key) {
      return `${i18n.t(key)}（${ex.code}）`;
    }
  }
  return transferErrorMessage(ex, fallback);
}

/** 解析 Content-Disposition 为下载文件名：优先 RFC 5987（filename*=UTF-8''…），回退普通 filename，最终回退调用方兜底名。 */
export function filenameFromDisposition(header: string | null, fallback: string): string {
  if (!header) {
    return fallback;
  }
  const ext = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/i.exec(header);
  if (ext?.[1]) {
    try {
      const decoded = decodeURIComponent(ext[1].trim().replace(/^"|"$/g, ''));
      if (decoded) {
        return decoded;
      }
    } catch {
      // 编码异常 → 回退普通 filename 解析
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(header);
  const name = plain?.[1]?.trim();
  return name || fallback;
}

/** 触发浏览器下载（Blob → objectURL → 隐形锚点；复用 web 侧 download 工具的落地方式，core 内可直接复用）。 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * 下载二进制端点（文件内容 / Excel 导出 / 模板）：
 * 鉴权 GET → blob；非 2xx 按信封转 ApiError（错误码可被 transferErrorMessage 转译）；
 * 文件名优先 Content-Disposition（后端保证下载原名），无则用 fallbackName，缺省回退 URL 末段。
 */
export async function downloadFile(path: string, fallbackName?: string): Promise<void> {
  const res = await apiFetchRaw(path, { method: 'GET' });
  if (!res.ok) {
    const envelope = (await res.json().catch(() => null)) as ApiEnvelope<unknown> | null;
    throw new ApiError(
      res.status,
      envelope?.code ?? 'DOWNLOAD_FAILED',
      envelope?.message ?? `下载失败（HTTP ${res.status}）`,
    );
  }
  const blob = await res.blob();
  const fallback = fallbackName ?? path.split('/').filter(Boolean).pop() ?? 'download';
  saveBlob(blob, filenameFromDisposition(res.headers.get('Content-Disposition'), fallback));
}

/** 直传业务归类（与 multipart 可选字段 bizType/bizId 同口径）。 */
export interface DirectUploadMeta {
  bizType?: string;
  bizId?: string;
}

/** 直传端点未就绪判定：preparation 404/501（老后端无直传端点/未启用）→ 回退 multipart 兼容。 */
function isDirectUploadUnsupported(ex: unknown): boolean {
  return ex instanceof ApiError && (ex.status === 404 || ex.status === 501);
}

/**
 * 对象存储直传（REQ-20261117-045754，UploadButton 主链路）：
 * preparation（校验+带租户段 objectKey+票据/预签名地址）→ PUT 原始字节 → completion（幂等落库）→ FileView。
 * - PUT 认证口径：LOCAL 票据端点为同源 /admin/… 走既有认证面（带 Authorization）；
 *   MINIO/OSS 预签名 URL 认证在签名 query，不带 Authorization（header 会与签名冲突）；
 * - 非 2xx 尽力解析 Result 信封错误码转 ApiError（LOCAL 票据端点信封；预签名 URL 错误体为云厂商格式）；
 * - 兜底兼容：preparation 404/501（端点不存在）→ 回退既有 multipart uploadFormData；
 * - 错误统一抛 ApiError，经 uploadErrorMessage 转译（含配额/图片/票据族码）。
 */
export async function uploadDirect(file: File, meta: DirectUploadMeta = {}): Promise<FileView> {
  const contentType = file.type || 'application/octet-stream';
  let preparation: DirectUploadPreparationView;
  try {
    preparation = await infraApi.fileDirectUploadPrepare({
      fileName: file.name,
      contentType,
      size: file.size,
      bizType: meta.bizType,
      bizId: meta.bizId,
    });
  } catch (ex: unknown) {
    if (isDirectUploadUnsupported(ex)) {
      return uploadFormData<FileView>(FILE_ENDPOINTS.base, file, meta);
    }
    throw ex;
  }

  // 附加签名头：preparation.headers 除 Content-Type 外逐键透传（Content-Type 由 rawPut 显式携带）
  const extraHeaders: Record<string, string> = {};
  for (const [name, value] of Object.entries(preparation.headers)) {
    if (name.toLowerCase() !== 'content-type') {
      extraHeaders[name] = value;
    }
  }
  const putRes = await rawPut(preparation.uploadUrl, file, {
    contentType: preparation.headers['Content-Type'] ?? contentType,
    auth: preparation.ticket != null,
    headers: extraHeaders,
  });
  if (!putRes.ok) {
    const envelope = (await putRes.json().catch(() => null)) as ApiEnvelope<unknown> | null;
    throw new ApiError(
      putRes.status,
      envelope?.code ?? 'DIRECT_UPLOAD_FAILED',
      envelope?.message ?? `直传失败（HTTP ${putRes.status}）`,
    );
  }

  return infraApi.fileDirectUploadComplete({
    objectKey: preparation.objectKey,
    fileName: file.name,
    contentType,
    size: file.size,
    bizType: meta.bizType,
    bizId: meta.bizId,
  });
}
