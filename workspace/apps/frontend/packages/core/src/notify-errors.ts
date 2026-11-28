import { ApiError } from './http/client';

/**
 * 通知域错误码 → 用户可读文案（REQ-20261114-120102；照 file-transfer.ts transferErrorMessage 风格，
 * 尾部附错误码便于定位）。键=REQ-20261128-051442 附录 A.3 全码真值（platform-system 08/09 段）；
 * 未命中已知码 → 透出后端 message（同样附码）或兜底文案。
 */
export const NOTIFY_ERROR_MESSAGES: Record<string, string> = {
  'platform-system-08-0001': '公告已发布，需先下线后再操作',
  'platform-system-08-0002': '公告不存在或已被删除',
  'platform-system-09-0002': '无权操作该站内信',
  'platform-system-09-0001': '站内信不存在或已被删除',
};

/** 通知域失败的用户可读文案：命中已知错误码 → 转译；尾部附错误码（转译提示口径对齐 file-transfer）。 */
export function notifyErrorMessage(ex: unknown, fallback = '操作失败，请稍后重试'): string {
  if (ex instanceof ApiError) {
    const base = NOTIFY_ERROR_MESSAGES[ex.code] ?? (ex.message || fallback);
    return `${base}（${ex.code}）`;
  }
  if (ex instanceof Error && ex.message) {
    return ex.message;
  }
  return fallback;
}
