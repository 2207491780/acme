import { ApiError, apiFetchRaw } from './http/client';
import type { ApiEnvelope } from './http/client';

/**
 * 上传/下载工具（REQ-20261114-120101 前端收敛一期）：
 * - 下载走 apiFetchRaw（鉴权 + 单次续约同源），按 Content-Disposition 还原原文件名；
 * - 上传（FormData）在 api/infra.ts 的 uploadFormData / infraApi.fileUpload；
 * - 既有错误码 FILE_SIZE_EXCEEDED / FILE_TYPE_REJECTED / EXCEL_PARSE_FAILED / FILE_NOT_FOUND
 *   统一经 transferErrorMessage 转译为用户可读提示（组件直接消费）。
 */

/** 传输域错误码 → 用户可读文案（命中即转译，未命中回退后端 message）。 */
export const TRANSFER_ERROR_MESSAGES: Record<string, string> = {
  FILE_SIZE_EXCEEDED: '文件大小超出限制',
  FILE_TYPE_REJECTED: '文件类型不被允许',
  EXCEL_PARSE_FAILED: 'Excel 文件解析失败，请确认文件为合法的 xlsx 格式',
  FILE_NOT_FOUND: '文件不存在或已被删除',
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
