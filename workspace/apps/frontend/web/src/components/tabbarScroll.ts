/**
 * 页签条滚动判定纯函数（REQ-20261120-104022 契约：判定逻辑唯一实现点，TabBar 组件与测试共用）。
 *
 * 背景：jsdom 无布局，溢出/到头/滚轮的行为断言依赖「判定逻辑抽纯函数」＋ jsdom property
 * override（scrollWidth/clientWidth/scrollLeft）；组件侧只消费本模块的判定结果做
 * rAF 节流的布尔翻转写 class，自身不做任何布局运算（性能红线见契约 §背景 / AC-18）。
 */

/** 行模式（deltaMode=1）滚轮的像素折算系数（契约定死：16px/行）。 */
export const WHEEL_PIXELS_PER_LINE = 16;

/** 箭头步进系数（契约 AC-7：步长 ≈ 可视宽的 80%）。 */
export const ARROW_STEP_RATIO = 0.8;

/**
 * 页签是否横向溢出（溢出才显示箭头/端点阴影并挂 wheel 监听）。
 */
export function isOverflowing(scrollWidth: number, clientWidth: number): boolean {
  return scrollWidth > clientWidth;
}

export interface TabbarEdgeState {
  /** 可向左滚（=左端有未显示内容）→ 左箭头可用 / 左端阴影显示。 */
  canLeft: boolean;
  /** 可向右滚（=右端有未显示内容）→ 右箭头可用 / 右端阴影显示。 */
  canRight: boolean;
}

/**
 * 到头判定：canLeft = scrollLeft > 0；canRight = scrollLeft < scrollWidth - clientWidth。
 * 非溢出（max ≤ 0）时两端均为 false（AC-3：溢出前无箭头/阴影行为）。
 */
export function edgeState(scrollLeft: number, clientWidth: number, scrollWidth: number): TabbarEdgeState {
  const maxScrollLeft = scrollWidth - clientWidth;
  return {
    canLeft: scrollLeft > 0,
    canRight: scrollLeft < maxScrollLeft,
  };
}

/**
 * 滚轮劫持判定（纯垂直滚轮意图才劫持为横向滚动）。
 *
 * 返回 null = 不劫持不 preventDefault（放行原生行为）：
 * - Ctrl/Cmd 修饰（AC-5）：放行页面缩放手势；
 * - |deltaX| ≥ |deltaY|（AC-9）：触控板双指横滑走原生横向滚动，不劫持不叠加二次加速。
 *
 * 返回数值 = 本次应施加的 scrollLeft 增量（deltaY>0 → 向右滚），deltaMode 归一：
 * - 0（像素）：原样；
 * - 1（行）：按 WHEEL_PIXELS_PER_LINE = 16px/行折算（契约定死）；
 * - 2（页）：契约未定义折算系数，按行折算同系数兜底（主流浏览器实际不产生页模式增量）。
 */
export function wheelDeltaToScroll(
  deltaMode: number,
  deltaX: number,
  deltaY: number,
  ctrlKey: boolean,
  metaKey: boolean,
): number | null {
  if (ctrlKey || metaKey) return null;
  if (Math.abs(deltaX) >= Math.abs(deltaY)) return null;
  const pixels = deltaMode === 0 ? deltaY : deltaY * WHEEL_PIXELS_PER_LINE;
  return pixels;
}
