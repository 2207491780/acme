/**
 * 首屏加载遮罩移除（REQ-20261120-120552）：splash 行为唯一实现点（纯函数依赖注入可测）。
 * index.html 内联静态遮罩 #app-boot（样式/品牌标/spinner 内联零外部请求）；本模块只裁决
 * 「何时淡出、何时移除」：
 * - 最短展示（AC-2）：距遮罩出现时间戳（index.html 引导脚本写入的 data-boot-at，或显式
 *   传入 bootAt）不足 minDisplayMs（默认 300ms）时延迟到足额再淡出，避免闪跳；
 * - 淡出：加 .app-boot-fade class（内联 CSS opacity 过渡，transitionMs 默认 400ms 与之一致），
 *   过渡结束后从 DOM 移除；
 * - 幂等：元素不存在 / 已进入淡出流程 / 无可用 document 时静默返回（main.tsx 首帧入口与
 *   ErrorBoundary 兜底入口双调用安全）。
 * data-boot-at 缺失/非法时按「已展示足额」处理（不人为拖延移除，防遮罩卡死）。
 */

/** 遮罩节点 id（index.html 内联节点一致）。 */
export const BOOT_ELEMENT_ID = 'app-boot';

/** 淡出 class（样式内联于 index.html，与 global.css 加载时序无关）。 */
export const APP_BOOT_FADE_CLASS = 'app-boot-fade';

/** 遮罩出现时间戳属性（index.html 引导脚本写入）。 */
export const BOOT_AT_ATTRIBUTE = 'data-boot-at';

export interface RemoveAppBootOptions {
  /** 遮罩出现时间戳（缺省读取 #app-boot[data-boot-at]）。 */
  bootAt?: number;
  /** 最短展示时长（默认 300ms）。 */
  minDisplayMs?: number;
  /** 当前时间（默认 Date.now()；测试注入）。 */
  now?: number;
  /** 文档对象（默认全局 document；测试注入）。 */
  document?: Document;
  /** 淡出过渡时长（默认 400ms，与内联 CSS 过渡一致）。 */
  transitionMs?: number;
}

function parseBootAt(el: Element): number {
  const raw = el.getAttribute(BOOT_AT_ATTRIBUTE);
  if (raw === null) return Number.NaN;
  const value = Number(raw);
  return Number.isFinite(value) ? value : Number.NaN;
}

export function removeAppBoot(options: RemoveAppBootOptions = {}): void {
  const doc = options.document ?? (typeof document !== 'undefined' ? document : undefined);
  if (!doc) return;
  const el = doc.getElementById(BOOT_ELEMENT_ID);
  if (!el || el.dataset.appBootFading === 'true') return;
  const now = options.now ?? Date.now();
  const minDisplayMs = options.minDisplayMs ?? 300;
  const transitionMs = options.transitionMs ?? 400;
  const bootAt = options.bootAt ?? parseBootAt(el);
  // 无时间戳 → 视为已足额（Infinity），立即进入淡出
  const elapsed = Number.isFinite(bootAt) ? Math.max(0, now - bootAt) : Number.POSITIVE_INFINITY;
  const delay = Math.max(0, minDisplayMs - elapsed);
  el.dataset.appBootFading = 'true';
  setTimeout(() => {
    if (!el.isConnected) return;
    el.classList.add(APP_BOOT_FADE_CLASS);
    setTimeout(() => {
      el.remove();
    }, transitionMs);
  }, delay);
}
