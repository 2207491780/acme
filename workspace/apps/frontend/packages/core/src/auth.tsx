import { cloneElement, createElement, Fragment, isValidElement } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { useMeStore } from './store/meStore';

/**
 * 按钮级权限原语（REQ-20261119-051119）：基于 me.permissions 的 fail-closed 显隐/禁用判定。
 * 权限源与 useCrud.can 同源（meStore.permissions），码面与后端 RequirePermissionInterceptor 一致；
 * 不引入新权限点——BUTTON 菜单节点只是既有 API 权限码的授权管理投影。
 */

/**
 * 权限判定 hook：code 命中 me.permissions 即通过。
 * - me 未加载（permissions 缺失）→ false（fail-closed，与 useCrud.can 口径一致）；
 * - code 为数组 → 任一命中即 true；未传 code → false（无可判定的授权语义）。
 */
export function useHasPermission(code?: string | string[]): boolean {
  const permissions = useMeStore((s) => s.me?.permissions);
  if (!permissions || code === undefined) {
    return false;
  }
  const codes = Array.isArray(code) ? code : [code];
  return codes.some((item) => permissions.includes(item));
}

/** 无权限时的表现：hide=不渲染（缺省）；disable=给子元素透传 disabled。 */
export type AuthMode = 'hide' | 'disable';

export interface AuthProps {
  /** 所需权限码（string 或数组；数组任一命中即通过）。 */
  code: string | string[];
  /** 无权限时的表现（缺省 hide）。 */
  mode?: AuthMode;
  children: ReactNode;
}

/** fieldset 回退的样式重置：不引入盒模型副作用（仅布局透明化，非视觉令牌）。 */
const FIELDSET_RESET: CSSProperties = { border: 0, padding: 0, margin: 0, display: 'contents' };

/**
 * 按钮级权限门组件：有权限渲染 children（原样，行为零变化），无权限按 mode 处理。
 * - hide（缺省）：不渲染（null）；
 * - disable：单一元素子节点经 cloneElement 透传 disabled（antd Button 等接受 disabled 的元素）；
 *   非元素子节点（文本/片段）包一层原生 disabled 容器（fieldset，禁用后代表单控件）。
 */
export function Auth({ code, mode = 'hide', children }: AuthProps): ReactElement | null {
  if (useHasPermission(code)) {
    return createElement(Fragment, null, children);
  }
  if (mode === 'disable') {
    if (isValidElement(children)) {
      // 单一元素 → 直接透传 disabled（最小实现，不包壳）
      return cloneElement(children, { disabled: true });
    }
    // 文本/多节点等非元素 children → 原生禁用容器兜底
    return createElement('fieldset', { disabled: true, style: FIELDSET_RESET }, children);
  }
  return null;
}
