import { cloneElement, useMemo, type ReactElement } from 'react';
import { useDateFormatStore, useLocaleStore, useTimezoneStore } from '@acme/core';

/**
 * 全局日期偏好重渲染门（缺陷回归：切换时区后页面时间列不更新）。
 *
 * 页面 element 是 routes.tsx 的模块级静态实例：父级重渲染被 React「同一元素引用 bail-out」
 * 屏蔽，页面子树不重渲染；而 formatDate 系 formatter 读 date-format 模块级全局缺省
 * （timezone/template/locale store 联动），页面自身缺订阅 → 偏好切换后时间列维持旧渲染。
 *
 * 本门订阅三偏好 store，任一变化即以 cloneElement 新元素引用强制页面重渲染（渲染期 formatter
 * 重读缺省即得新值）；偏好未变时经 useMemo 保持同一引用，保活语义不破坏（切签/父级例行重渲染
 * 如 1.2s TICK 不带动页面重渲染）。
 */
export function DateTimePrefsRefresh({ children }: { children: ReactElement }) {
  const timezone = useTimezoneStore((s) => s.timezone);
  const template = useDateFormatStore((s) => s.template);
  const locale = useLocaleStore((s) => s.locale);
  // 依赖仅偏好值与 children：父级重渲染（偏好未变）复用同一克隆引用 → 子树照旧 bail-out
  return useMemo(() => cloneElement(children), [children, timezone, template, locale]);
}
