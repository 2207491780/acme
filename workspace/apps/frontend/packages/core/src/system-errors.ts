import { ApiError } from './http/client';
import { i18n } from './i18n';

/**
 * 系统域错误码 → 用户可读文案（REQ-20261114-120104；notify-errors.ts 风格，尾部附码便于定位）。
 * 文案取自 i18n 资源包（depts/positions/roles 域），随当前语言切换；未命中已知码 → 透出后端
 * message（同样附码）或兜底文案（兜底由调用方以 t() 传入，本模块不内联文案）。
 */

/** 已知系统域错误码 → i18n key（REQ-20261128-051442 附录 A.3/A.4 全码真值作键；
 *  SESSION/REVOKE 两键跨域归位 platform-auth-04 段；i18n key 值与文案不变）。 */
export const SYSTEM_ERROR_I18N_KEYS: Record<string, string> = {
  'platform-system-10-0002': 'depts.errors.deptHasChildren',
  'platform-system-10-0003': 'depts.errors.deptHasUsers',
  'platform-system-11-0002': 'positions.errors.positionInUse',
  'platform-system-02-0008': 'roles.scope.invalid',
  'platform-auth-04-0001': 'onlineUsers.errors.sessionNotFound',
  'platform-auth-04-0002': 'onlineUsers.errors.revokeSuperadminForbidden',
};

/** 系统域失败的用户可读文案：命中已知错误码 → 转译；尾部附错误码（转译提示口径对齐 notify-errors）。 */
export function systemErrorMessage(ex: unknown, fallback: string): string {
  if (ex instanceof ApiError) {
    const key = SYSTEM_ERROR_I18N_KEYS[ex.code];
    const base = key ? i18n.t(key) : (ex.message || fallback);
    return `${base}（${ex.code}）`;
  }
  if (ex instanceof Error && ex.message) {
    return ex.message;
  }
  return fallback;
}
