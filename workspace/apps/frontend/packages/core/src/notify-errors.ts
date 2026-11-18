import { ApiError } from './http/client';

/**
 * 通知域错误码 → 用户可读文案（REQ-20261114-120102；照 file-transfer.ts transferErrorMessage 风格，
 * 尾部附错误码便于定位）。未命中已知码 → 透出后端 message（同样附码）或兜底文案。
 */
export const NOTIFY_ERROR_MESSAGES: Record<string, string> = {
  NOTICE_PUBLISHED: '公告已发布，需先下线后再操作',
  NOTICE_NOT_FOUND: '公告不存在或已被删除',
  MESSAGE_FORBIDDEN: '无权操作该站内信',
  MESSAGE_NOT_FOUND: '站内信不存在或已被删除',
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
