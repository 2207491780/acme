/**
 * REQ-20261120-104022：页签条滚动增强（箭头/滚轮/分隔符/端点阴影）＋hover 结构化预览卡。
 *
 * 覆盖（AC-1~18 全量，映射表随交付回填契约验收标准节）：
 * - 纯函数直测 src/components/tabbarScroll（契约变更清单定名的判定逻辑唯一实现点）：
 *   isOverflowing / edgeState / wheelDeltaToScroll；
 * - 溢出/滚动类：jsdom property override 构造 scrollWidth/clientWidth/scrollLeft；
 * - CSS 视觉走源级锚（读 global.css 源码断言目标规则，先例=REQ20261119063129 迭代1 视觉锚用例）；
 * - 预览卡：App 级 hover 链路（Portal/debounce/分支/关闭）＋组件直测（TabPreviewCard props 契约）。
 *
 * 装配照范本 web/tests/REQ20261119063129-tabs.test.tsx：seedSession + useMeStore 播种 +
 * HashRouter + <AntApp> + matchMedia/ResizeObserver stub + testTimeout ≥15000ms；
 * 页签/卡片 label 断言一律收敛作用域（同源文案在侧边导航/页面标题会多命中）。
 *
 * jsdom 无布局的断言策略（契约背景节）：
 * - 几何量一律 property override（可写数据属性遮蔽原型访问器），afterEach 统一删除还原；
 * - rAF 手动排队桩（flushRaf 排空）：溢出/到头判定经 rAF 节流，测试可控地逐帧推进；
 * - scrollIntoView jsdom 未实现 → 原型级录制桩（AC-8 断言调用参数与目标元素）；
 * - 滚动事件 jsdom 不因 scrollLeft 赋值自动派发 → 断言前手动 fireEvent.scroll 驱动判定。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentType, ReactNode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useTabsStore } from '../src/store/tabsStore';
import { useAuthStore, useLockStore, useMeStore, useNotifyStore } from '@acme/core';
import { edgeState, isOverflowing, wheelDeltaToScroll } from '../src/components/tabbarScroll';
import * as TabPreviewCardModule from '../src/components/TabPreviewCard';

vi.setConfig({ testTimeout: 30000 });

/**
 * TabPreviewCard 导出形式契约未写死（default/具名）——命名空间导入两者兼容。
 * props 形状照契约变更清单：{ tab: TabItem; icon: ReactNode; label: string;
 * onRefresh(path)/onTogglePin(path)/onClose(path) }（TabItem 实例从真实 store 取，避免照抄其内部形状）。
 */
const TabPreviewCard = ((TabPreviewCardModule as Record<string, unknown>).default
  ?? (TabPreviewCardModule as Record<string, unknown>).TabPreviewCard) as unknown as ComponentType<{
  tab: unknown;
  icon: ReactNode;
  label: string;
  onRefresh(path: string): void;
  onTogglePin(path: string): void;
  onClose(path: string): void;
}>;

/**
 * 全套件并行负载下统一 10s 上限的 waitFor（nav.test 同款放宽口径）。
 * 回调类型泛型透传：RTL waitFor 以回调返回值 resolve，App 级预览卡用例依赖该返回值定位卡片。
 */
const wait = <T,>(assertion: () => T, timeout = 10000) => waitFor(assertion, { timeout });

const sleep = (ms: number) => new Promise((resolve_) => setTimeout(resolve_, ms));

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const stubBrowserGaps = () => {
  // min-width 断点置 true：App 按桌面布局渲染（nav.test 装配口径）
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** App 整棵装配（范本口径）：伴随请求统一回空信封；routes 允许补特定端点。 */
const renderAppAt = async (
  hash: string,
  options: { permissions?: string[]; routes?: Record<string, () => Response> } = {},
) => {
  seedSession();
  const { permissions = [], routes = {} } = options;
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions, menus: [] });
    }
    for (const [fragment, respond] of Object.entries(routes)) {
      if (url.pathname.includes(fragment)) return respond();
    }
    return envelope(null); // Dashboard 等伴随请求
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const tabbar = (): HTMLElement => {
  const el = document.querySelector('[role="tablist"]');
  if (!el) throw new Error('页签条未渲染');
  return el as HTMLElement;
};

const tabEls = (): HTMLElement[] => Array.from(document.querySelectorAll('.tabbar-tab')) as HTMLElement[];

const tabEl = (path: string): HTMLElement | null =>
  document.querySelector(`.tabbar-tab[data-tab-path="${path}"]`) as HTMLElement | null;

/** 三页签装配：/（permanent 首位）＋ /apps ＋ /users（激活，权限门禁放行）。 */
const seedThreeTabs = async () => {
  await renderAppAt('#/', {
    permissions: ['system:user:list'],
    routes: { '/system/users': () => envelope({ records: [], total: 0 }) },
  });
  await wait(() => expect(tabEl('/')).toBeTruthy());
  window.location.hash = '#/apps';
  await wait(() => expect(tabEl('/apps')).toBeTruthy());
  window.location.hash = '#/users';
  await wait(() => expect(tabEl('/users')).toBeTruthy());
  expect(tabEls()).toHaveLength(3);
};

/** 双页签装配：/（permanent 首位）＋ /users（激活），供组件直测取真实 TabItem。 */
const seedTwoTabs = async () => {
  await renderAppAt('#/users', {
    permissions: ['system:user:list'],
    routes: { '/system/users': () => envelope({ records: [], total: 0 }) },
  });
  await wait(() => expect(tabEl('/users')).toBeTruthy());
};

// ————— rAF 手动排队桩：溢出/到头判定经 rAF 节流，测试可控逐帧推进（AC-18） —————
let rafSeq = 0;
let rafCbs = new Map<number, FrameRequestCallback>();

const installRafStub = () => {
  rafSeq = 0;
  rafCbs = new Map();
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    rafSeq += 1;
    rafCbs.set(rafSeq, cb);
    return rafSeq;
  });
  vi.stubGlobal('cancelAnimationFrame', (id: number) => {
    rafCbs.delete(id);
  });
};

/** 排空当前帧队列（回调内可能再排下一帧 → 循环排空，设防死循环上限）。 */
const flushRaf = () => {
  for (let guard = 0; rafCbs.size > 0 && guard < 50; guard += 1) {
    const cbs = [...rafCbs.values()];
    rafCbs.clear();
    for (const cb of cbs) cb(Date.now());
  }
};

/** 当前挂起未执行的帧回调数（AC-18 节流断言：密集 scroll 突发后挂起帧应被复用/取消）。 */
const pendingRafCount = () => rafCbs.size;

// ————— jsdom 几何 property override：scrollWidth/clientWidth/scrollLeft —————
let geometryTargets: HTMLElement[] = [];

type BarGeometry = { scrollWidth?: number; clientWidth?: number; scrollLeft?: number };

const applyGeometry = (bar: HTMLElement, geo: BarGeometry) => {
  if (!geometryTargets.includes(bar)) geometryTargets.push(bar);
  for (const [key, value] of Object.entries(geo) as [keyof BarGeometry, number][]) {
    Object.defineProperty(bar, key, { value, configurable: true, writable: true });
  }
};

const clearGeometry = () => {
  for (const el of geometryTargets) {
    for (const key of ['scrollWidth', 'clientWidth', 'scrollLeft'] as const) {
      delete (el as unknown as Record<string, unknown>)[key];
    }
  }
  geometryTargets = [];
};

/**
 * 溢出场景装配：三页签＋几何 override（可视宽 300 / 总宽 600 / 最大滚距 300）。
 * 溢出翻转触发双通道（实现侧触发器契约未写死，两路都发保证可达）：
 * ① scroll 事件（契约 AC-18：滚动事件驱动溢出/到头判定，rAF 节流）；
 * ② 一次 togglePin 往返（pins 店态两次 set → 触发依赖 tabs 的重测 effect；净效果零变化）。
 */
const renderOverflowingTabs = async (scrollLeft = 0) => {
  await seedThreeTabs();
  const bar = tabbar();
  applyGeometry(bar, { scrollWidth: 600, clientWidth: 300, scrollLeft });
  fireEvent.scroll(bar);
  flushRaf();
  useTabsStore.getState().togglePin('/apps');
  useTabsStore.getState().togglePin('/apps');
  flushRaf();
  await wait(() => {
    expect(arrowEl('left')).toBeTruthy();
    expect(arrowEl('right')).toBeTruthy();
  });
  return bar;
};

// ————— 箭头元素/disabled 口径 —————
const arrowEl = (side: 'left' | 'right'): HTMLButtonElement | null =>
  document.querySelector(`.tabbar-arrow-${side}`) as HTMLButtonElement | null;

const isDisabled = (el: Element | null): boolean =>
  !!el && ((el as HTMLButtonElement).disabled === true || el.hasAttribute('disabled'));

// ————— scrollIntoView 原型级录制桩（jsdom 未实现；AC-8 断言调用参数与目标元素） —————
const sivOwner: typeof Element.prototype = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollIntoView')
  ? Element.prototype
  : HTMLElement.prototype;
const sivDescriptor = Object.getOwnPropertyDescriptor(sivOwner, 'scrollIntoView');
let sivCalls: { el: Element; options: unknown }[] = [];

const installScrollIntoViewStub = () => {
  sivCalls = [];
  const stub = function scrollIntoViewStub(this: Element, ...args: unknown[]) {
    sivCalls.push({ el: this, options: args[0] });
  };
  Object.defineProperty(sivOwner, 'scrollIntoView', { value: stub, configurable: true, writable: true });
};

const restoreScrollIntoView = () => {
  if (sivDescriptor) Object.defineProperty(sivOwner, 'scrollIntoView', sivDescriptor);
  else delete (sivOwner as unknown as Record<string, unknown>).scrollIntoView;
};

// ————— wheel 监听挂载探针（AC-3 不挂 / AC-4 仅挂 .tabbar 且 passive:false） —————
type WheelListenerRecord = { target: EventTarget; options?: AddEventListenerOptions };
type AddEventListenerFn = typeof EventTarget.prototype.addEventListener;
const nativeAdd: AddEventListenerFn = EventTarget.prototype.addEventListener;
let wheelRecs: WheelListenerRecord[] = [];
let wheelSpyPatched = false;

const installWheelSpy = () => {
  wheelRecs = [];
  EventTarget.prototype.addEventListener = function addEventListenerSpy(
    this: EventTarget,
    type: string,
    listener: EventListenerOrEventListenerObject,
    options?: boolean | AddEventListenerOptions,
  ) {
    if (type === 'wheel') {
      wheelRecs.push({ target: this, options: typeof options === 'object' ? options : undefined });
    }
    return nativeAdd.call(this, type, listener, options);
  } as unknown as AddEventListenerFn;
  wheelSpyPatched = true;
};

const restoreWheelSpy = () => {
  if (wheelSpyPatched) {
    EventTarget.prototype.addEventListener = nativeAdd;
    wheelSpyPatched = false;
    wheelRecs = [];
  }
};

// ————— MutationObserver：class/style 属性写入观察（AC-17 翻转出现 / AC-18 仅翻转时写） —————
type AttrWrite = { target: Element; attributeName: string | null };

const watchAttrWrites = (root: HTMLElement, attributeFilter: string[] = ['class', 'style']) => {
  const records: AttrWrite[] = [];
  const observer = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'attributes') records.push({ target: m.target as Element, attributeName: m.attributeName });
    }
  });
  observer.observe(root, { subtree: true, attributes: true, attributeFilter, attributeOldValue: true });
  return { records, disconnect: () => observer.disconnect() };
};

/** MutationObserver 回调走微任务 → 等一个宏任务边界确保记录落袋。 */
const flushMutations = () => sleep(0);

// ————— 预览卡定位（Portal 挂 body：从 label 叶子上溯到 body 直接子元素） —————
const previewCardRoot = (leaf: HTMLElement): HTMLElement => {
  let el: HTMLElement = leaf;
  while (el.parentElement && el.parentElement !== document.body) el = el.parentElement;
  return el;
};

/**
 * antd 关闭的弹层会保留隐藏 DOM（范本 dropdown 先例）——预览卡负向断言（未出现/已关闭）
 * 必须排除隐藏子树，避免陈旧弹层造成误命中。
 */
const isHiddenSubtree = (el: Element): boolean => {
  let node: Element | null = el;
  while (node && node !== document.body) {
    if ((node as HTMLElement).style?.display === 'none') return true;
    if (Array.from(node.classList).some((c) => /(^|-)hidden$/.test(c))) return true;
    node = node.parentElement;
  }
  return false;
};

/** 卡根须含快捷操作钮（刷新/固定/取消固定/关闭 至少其一）——排除面包屑等无操作钮的同名文本容器。 */
const hasActionButton = (root: HTMLElement): boolean =>
  Array.from(root.querySelectorAll('button')).some((b) => {
    const name = (b.getAttribute('aria-label') ?? b.textContent ?? '').trim();
    return name === '刷新' || name === '固定' || name === '取消固定' || name === '关闭';
  });

/**
 * 按 label 定位预览卡根（找不到返回 null）：
 * 候选=该文本的节点中不在页签条/页签面板/侧边菜单/隐藏弹层内的，且其 body 直接子级根
 * 含快捷操作钮（同源文案多命中收敛，范本口径；三种卡分支均必有「刷新」钮）。
 */
const findPreviewCard = (label: string): HTMLElement | null => {
  const bar = tabbar();
  const candidates = screen.queryAllByText(label).filter((el) =>
    !bar.contains(el)
    && !el.closest('[role="tabpanel"]')
    && !el.closest('.ant-menu')
    && !el.closest('[role="menuitem"]')
    && !isHiddenSubtree(el));
  for (const leaf of candidates) {
    const root = previewCardRoot(leaf);
    if (hasActionButton(root)) return root;
  }
  return null;
};

/**
 * hover 触发：契约未写死指针事件 API（pointerenter/mouseenter）——两族事件都派发，两种实现均可达。
 * RTL 的 Enter/Leave 会同时派发 over+enter 成对事件，覆盖 React 悬停合成的两条通道。
 */
const hoverTab = (el: HTMLElement): void => {
  fireEvent.mouseEnter(el);
  fireEvent.pointerEnter(el);
};

const unhoverTab = (el: HTMLElement): void => {
  fireEvent.mouseLeave(el);
  fireEvent.pointerLeave(el);
};

// ————— global.css 源级锚共享读取（CSS 视觉锚 describe 与 AC-3 的 class 显隐锚共用） —————
// jsdom 环境的 URL 构造器产出的实例不被 node:fs 接受 → 经 fileURLToPath 转本地路径再读
const globalCss = readFileSync(resolve(fileURLToPath(import.meta.url), '../../src/styles/global.css'), 'utf8');

/** tabbar 相关单行规则提取（global.css 页签条段为「selector { body }」单行格式，同范本先例）。 */
const tabbarRules = (): { selector: string; body: string }[] =>
  [...globalCss.matchAll(/([^{}\n]*tabbar[^{}\n]*)\{([^}]*)\}/g)].map((m) => ({ selector: m[1].trim(), body: m[2] }));

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  stubBrowserGaps();
  installRafStub();
  installScrollIntoViewStub();
});

afterEach(() => {
  cleanup();
  clearGeometry();
  restoreScrollIntoView();
  restoreWheelSpy();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
});

// ————— 纯函数直测：判定逻辑唯一实现点（AC-3/4/5/6/7/9 的逻辑口径） —————
describe('REQ20261120104022 纯函数 tabbarScroll', () => {
  it('should_report_overflow_only_when_scroll_width_exceeds_client_width', () => {
    expect(isOverflowing(600, 300)).toBe(true);
    expect(isOverflowing(300, 300)).toBe(false); // AC-3：总宽 ≤ 可视宽不算溢出
    expect(isOverflowing(299, 300)).toBe(false);
  });

  it('should_disable_both_edges_when_content_fits_client_width', () => {
    expect(edgeState(0, 300, 300)).toEqual({ canLeft: false, canRight: false });
  });

  it('should_disable_left_arrow_only_at_left_edge', () => {
    expect(edgeState(0, 300, 600)).toEqual({ canLeft: false, canRight: true });
  });

  it('should_enable_both_arrows_between_edges', () => {
    expect(edgeState(1, 300, 600)).toEqual({ canLeft: true, canRight: true });
    expect(edgeState(150, 300, 600)).toEqual({ canLeft: true, canRight: true });
    expect(edgeState(299, 300, 600)).toEqual({ canLeft: true, canRight: true });
  });

  it('should_disable_right_arrow_only_at_right_edge', () => {
    expect(edgeState(300, 300, 600)).toEqual({ canLeft: true, canRight: false });
  });

  it('should_map_pure_vertical_wheel_to_signed_scroll_delta', () => {
    expect(wheelDeltaToScroll(0, 0, 100, false, false)).toBe(100);
    expect(wheelDeltaToScroll(0, 0, -100, false, false)).toBe(-100); // 向上滚 = 向左滚（带符号增量）
  });

  it('should_pass_through_wheel_when_ctrl_or_cmd_pressed', () => {
    expect(wheelDeltaToScroll(0, 0, 100, true, false)).toBeNull(); // Ctrl 放行
    expect(wheelDeltaToScroll(0, 0, 100, false, true)).toBeNull(); // Cmd 放行
    expect(wheelDeltaToScroll(1, 0, 3, true, false)).toBeNull();   // 行模式下同样放行
  });

  it('should_pass_through_wheel_when_horizontal_intent', () => {
    expect(wheelDeltaToScroll(0, 100, 100, false, false)).toBeNull(); // |dx| ≥ |dy| 边界放行
    expect(wheelDeltaToScroll(0, 120, 50, false, false)).toBeNull();
  });

  it('should_normalize_row_delta_mode_by_16px_per_row', () => {
    expect(wheelDeltaToScroll(1, 0, 3, false, false)).toBe(48);
    expect(wheelDeltaToScroll(1, 0, -2, false, false)).toBe(-32);
  });
});

// ————— CSS 源级视觉锚（先例=REQ20261119063129 迭代1 视觉锚用例/global-css.test.ts） —————
describe('REQ20261120104022 CSS 源级视觉锚（AC-1/AC-2/AC-17＋箭头）', () => {
  // css/tabbarRules 已上移模块级（AC-3 的 class 显隐锚共用），本 describe 用例体不变
  it('should_render_adjacent_separator_by_css_pseudo_when_tabs_adjacent', async () => {
    // AC-1：相邻分隔符=纯 CSS 相邻兄弟伪元素（+::before、1px），不新增 DOM 节点
    const sepRules = tabbarRules().filter((r) => r.selector.includes('+') && /::?before/.test(r.selector));
    expect(sepRules.length, 'global.css 缺少相邻分隔符 +::before 规则').toBeGreaterThan(0);
    expect(sepRules.some((r) => r.body.includes('1px')), '分隔符规则未声明 1px').toBe(true);

    await seedThreeTabs();
    expect(tabEls()).toHaveLength(3);
    expect(document.querySelectorAll('.tabbar-sep'), '分隔符不得产生 DOM 节点').toHaveLength(0);
  });

  it('should_fade_adjacent_separator_when_tab_hovered_or_active', () => {
    // AC-2：hover/active 相邻分隔符淡出至 opacity:0，且带 opacity 过渡（Chrome 降噪惯例）
    const rules = tabbarRules();
    const fadeRules = rules.filter((r) => /::?before/.test(r.selector)
      && (r.selector.includes(':hover') || r.selector.includes('-active'))
      && /opacity\s*:\s*0\b/.test(r.body));
    expect(fadeRules.length, '缺少 hover/active 相邻分隔符淡出规则').toBeGreaterThan(0);
    const sepRules = rules.filter((r) => r.selector.includes('+') && /::?before/.test(r.selector));
    const hasOpacityTransition = [...fadeRules, ...sepRules]
      .some((r) => /transition\s*:[^;]*opacity/.test(r.body));
    expect(hasOpacityTransition, '分隔符淡出缺 opacity 过渡').toBe(true);
  });

  it('should_show_gradient_endpoint_shadow_with_opacity_transition_under_300ms', () => {
    // AC-17（TL 裁决①：放宽伪元素承载）——真实元素 .tabbar-shadow 与伪元素承载均合规；
    // 锚点=tabbar 阴影规则组整体具备：linear-gradient＋绝对定位＋opacity 态＋opacity 过渡 ≤300ms＋pointer-events:none
    const shadowGroup = tabbarRules().filter((r) => r.body.includes('linear-gradient') || r.selector.includes('shadow'));
    expect(shadowGroup.length, 'global.css 缺少端点阴影规则组（linear-gradient 或 shadow 命名）').toBeGreaterThan(0);
    expect(shadowGroup.some((r) => r.body.includes('pointer-events: none')), '端点阴影规则组缺 pointer-events:none').toBe(true);
    expect(shadowGroup.some((r) => /position\s*:\s*absolute/.test(r.body)), '端点阴影规则组缺绝对定位').toBe(true);
    expect(shadowGroup.some((r) => r.body.includes('opacity')), '端点阴影规则组缺 opacity 态').toBe(true);
    const transitionRule = shadowGroup.find((r) => /transition\s*:[^;]*opacity/.test(r.body));
    expect(transitionRule, '端点阴影规则组缺 opacity 过渡').toBeTruthy();
    const dur = transitionRule!.body.match(/transition\s*:[^;]*?(\d*\.?\d+)(ms|s)\b/);
    expect(dur, 'opacity 过渡未声明时长').toBeTruthy();
    const ms = dur![2] === 's' ? parseFloat(dur![1]) * 1000 : parseFloat(dur![1]);
    expect(ms, `opacity 过渡应 ≤300ms（实际 ${ms}ms）`).toBeLessThanOrEqual(300);
  });

  it('should_float_arrows_with_disabled_state_style_when_overflowing', () => {
    // 契约变更清单：.tabbar-arrow 两端浮层、禁用态（AC-6 视觉置灰 / AC-7 浮层定位的源级锚）
    const arrowRules = tabbarRules().filter((r) => r.selector.includes('.tabbar-arrow'));
    expect(arrowRules.length, 'global.css 缺少 .tabbar-arrow 规则').toBeGreaterThan(0);
    expect(arrowRules.some((r) => /position\s*:\s*absolute/.test(r.body)), '箭头未做浮层定位').toBe(true);
    expect(arrowRules.some((r) => /(:disabled|\[disabled\])/.test(r.selector)), '箭头缺禁用态规则').toBe(true);
  });
});

// ————— AC-3：无溢出时行为不变 —————
describe('REQ20261120104022 AC-3 无溢出行为不变', () => {
  it('should_render_no_arrows_and_no_wheel_listener_when_content_fits', async () => {
    installWheelSpy();
    await seedThreeTabs();
    await sleep(50); // 等 mount effect 全部落定
    // jsdom 无布局：scrollWidth/clientWidth 均为 0 → 非溢出（isOverflowing(0,0)=false 已在纯函数锁定）
    expect(isOverflowing(0, 0)).toBe(false);
    // TL 裁决③：箭头 DOM 常驻＋class 显隐＝合规（与端点阴影同口径）→ 断言溢出态 class 缺席而非 DOM 缺席
    expect(tabbar().classList.contains('tabbar-overflowing'), '未溢出 bar 不得带 tabbar-overflowing class').toBe(false);
    // CSS 源级锚：.tabbar-arrow 默认 display:none（仅溢出态经 .tabbar-overflowing 兄弟选择器显示）
    const arrowHiddenRule = tabbarRules().find(
      (r) => r.selector.includes('.tabbar-arrow') && r.body.includes('display: none'),
    );
    expect(arrowHiddenRule, 'global.css 缺少 .tabbar-arrow 默认 display:none 规则').toBeTruthy();
    // React 18 自身在 root 容器以 passive 挂 wheel 委托监听 → 只考察挂在 .tabbar 元素上的
    expect(wheelRecs.filter((r) => r.target === tabbar()), '未溢出不得在页签条上挂 wheel 监听').toHaveLength(0);
  });
});

// ————— AC-4/AC-5/AC-9：溢出显示与滚轮劫持边界 —————
describe('REQ20261120104022 AC-4/5/9 溢出显示与滚轮放行', () => {
  it('should_show_arrows_attach_wheel_listener_and_scroll_horizontally_when_vertical_wheel', async () => {
    installWheelSpy();
    const bar = await renderOverflowingTabs(0);
    // 两端箭头出现（aria-label 走 i18n，值非空即可——具体译文契约未写死）
    expect(arrowEl('left')!.getAttribute('aria-label'), '左箭头缺 aria-label').toBeTruthy();
    expect(arrowEl('right')!.getAttribute('aria-label'), '右箭头缺 aria-label').toBeTruthy();
    // wheel 监听仅挂 .tabbar 且 passive:false（性能红线；React root 自身的 wheel 委托监听按 target 过滤排除）
    await wait(() => expect(wheelRecs.filter((r) => r.target === bar).length).toBeGreaterThanOrEqual(1));
    const onBar = wheelRecs.filter((r) => r.target === bar);
    expect(onBar.every((r) => r.options?.passive === false), 'wheel 监听应 passive:false').toBe(true);
    // 垂直滚轮 → 页签条横向滚动（步长=deltaY）
    fireEvent.wheel(bar, { deltaY: 100 });
    expect(bar.scrollLeft, '垂直滚轮应劫持为横向滚动').toBe(100);
  });

  it('should_pass_through_wheel_when_ctrl_or_cmd_modifier_held', async () => {
    installWheelSpy();
    const bar = await renderOverflowingTabs(0);
    // 先等实现侧监听器确实挂上（探针注册须晚于它，才能读到其 preventDefault 之后的状态）
    await wait(() => expect(wheelRecs.filter((r) => r.target === bar).length).toBeGreaterThanOrEqual(1));
    const prevented: boolean[] = [];
    const probe = (e: Event): void => { prevented.push(e.defaultPrevented); };
    bar.addEventListener('wheel', probe);
    fireEvent.wheel(bar, { deltaY: 100, ctrlKey: true });
    expect(bar.scrollLeft, 'Ctrl 滚轮不得映射滚动').toBe(0);
    expect(prevented.at(-1), 'Ctrl 滚轮必须放行（不可 preventDefault）').toBe(false);
    fireEvent.wheel(bar, { deltaY: 100, metaKey: true });
    expect(bar.scrollLeft, 'Cmd 滚轮不得映射滚动').toBe(0);
    expect(prevented.at(-1), 'Cmd 滚轮必须放行').toBe(false);
    bar.removeEventListener('wheel', probe);
  });

  it('should_not_hijack_horizontal_trackpad_wheel', async () => {
    installWheelSpy();
    const bar = await renderOverflowingTabs(0);
    await wait(() => expect(wheelRecs.filter((r) => r.target === bar).length).toBeGreaterThanOrEqual(1));
    const prevented: boolean[] = [];
    const probe = (e: Event): void => { prevented.push(e.defaultPrevented); };
    bar.addEventListener('wheel', probe);
    fireEvent.wheel(bar, { deltaX: 120, deltaY: 50 });
    expect(bar.scrollLeft, '横滑意图（|dx|>|dy|）不得劫持，走原生横向滚动').toBe(0);
    expect(prevented.at(-1)).toBe(false);
    fireEvent.wheel(bar, { deltaX: 100, deltaY: 100 });
    expect(bar.scrollLeft, '|dx|=|dy| 边界同样放行').toBe(0);
    expect(prevented.at(-1)).toBe(false);
    bar.removeEventListener('wheel', probe);
  });
});

// ————— AC-6/AC-7：箭头步进与到头禁用 —————
describe('REQ20261120104022 AC-6/7 箭头步进与到头禁用', () => {
  it('should_disable_edge_arrow_and_keep_scroll_when_already_at_edge', async () => {
    const bar = await renderOverflowingTabs(0);
    await wait(() => expect(isDisabled(arrowEl('left'))).toBe(true)); // 最左：左箭头禁用（对应端阴影隐藏由 edgeState/AC-17 用例覆盖）
    await wait(() => expect(isDisabled(arrowEl('right'))).toBe(false));
    fireEvent.click(arrowEl('left')!); // 禁用态点击无效
    fireEvent.scroll(bar);
    flushRaf();
    expect(bar.scrollLeft, '禁用箭头点击不得滚动').toBe(0);
    bar.scrollLeft = 300; // 滚到最右
    fireEvent.scroll(bar);
    flushRaf();
    await wait(() => expect(isDisabled(arrowEl('right'))).toBe(true));
    await wait(() => expect(isDisabled(arrowEl('left'))).toBe(false));
  });

  it('should_step_scroll_by_about_80_percent_of_client_width_when_arrow_clicked', async () => {
    const bar = await renderOverflowingTabs(0);
    fireEvent.click(arrowEl('right')!);
    fireEvent.scroll(bar);
    flushRaf();
    expect(Math.abs(bar.scrollLeft - 240), '步长应≈可视宽 80%（300×0.8=240）').toBeLessThanOrEqual(1);
    const right = arrowEl('right')!;
    if (!isDisabled(right)) { // 连续点击可重复推进
      fireEvent.click(right);
      fireEvent.scroll(bar);
      flushRaf();
    }
    expect(bar.scrollLeft, '连续点击推进并封顶到最右（300=600-300）').toBeGreaterThanOrEqual(300);
    await wait(() => expect(isDisabled(arrowEl('right'))).toBe(true)); // 到头禁用
  });
});

// ————— AC-8：activePath 变化 → 活动签 scrollIntoView —————
describe('REQ20261120104022 AC-8 活动签滚动跟随', () => {
  it('should_scroll_active_tab_into_view_nearest_when_active_path_changes', async () => {
    await renderOverflowingTabs(300); // 停在最右：最左的 '/' 完整位于可视区左侧外
    sivCalls.length = 0; // 只考察本次 activePath 变化触发的调用
    window.location.hash = '#/';
    await wait(() => {
      expect(sivCalls.some((c) => c.el === tabEl('/'))).toBe(true);
    });
    const call = sivCalls.filter((c) => c.el === tabEl('/')).at(-1)!;
    const options = call.options as ScrollIntoViewOptions | undefined;
    expect(options?.inline, 'inline=nearest（不强制居中）').toBe('nearest');
    expect(options?.block).toBe('nearest');
  });
});

// ————— AC-10~16：hover 结构化预览卡（App 级链路） —————
describe('REQ20261120104022 AC-10~16 hover 预览卡', () => {
  it('should_show_structured_preview_card_with_full_title_and_actions_when_hover_lingers', async () => {
    // AC-10：非活动签 hover ≥150ms → Portal 预览卡（图标＋完整标题＋刷新/固定/关闭）
    await seedThreeTabs();
    hoverTab(tabEl('/apps')!);
    expect(findPreviewCard('应用结构'), 'debounce 内不得立即出现').toBeNull();
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('应用结构');
      expect(found, 'hover 停留后应浮出预览卡').toBeTruthy();
      return found;
    }) as HTMLElement;
    // Portal：不挂在页签条内、不挂在页签面板内（挂 body）
    expect(tabbar().contains(card)).toBe(false);
    expect(card.closest('[role="tabpanel"]')).toBeNull();
    expect(card.querySelector('svg'), '预览卡应含页签图标').toBeTruthy();
    expect(within(card).getByText('应用结构')).toBeTruthy(); // 完整标题（截断为 CSS ellipsis，DOM 含全文）
    expect(within(card).getByRole('button', { name: '刷新' })).toBeTruthy();
    expect(within(card).getByRole('button', { name: '固定' })).toBeTruthy();
    expect(within(card).getByRole('button', { name: '关闭' })).toBeTruthy();
  });

  it('should_omit_close_and_pin_actions_when_permanent_tab_hovered', async () => {
    // AC-11：permanent 首页签（非活动态）→ 预览卡无关闭钮、无固定钮
    await seedThreeTabs(); // 激活 /users → '/' 非活动
    hoverTab(tabEl('/')!);
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('概览');
      expect(found, 'permanent 非活动签仍应浮出预览卡').toBeTruthy();
      return found;
    }) as HTMLElement;
    expect(within(card).getByRole('button', { name: '刷新' })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: '关闭' })).toBeNull();
    expect(within(card).queryByRole('button', { name: '固定' })).toBeNull();
    expect(within(card).queryByRole('button', { name: '取消固定' })).toBeNull();
  });

  it('should_show_unpin_without_close_when_pinned_tab_hovered', async () => {
    // AC-12：pinned 签 → 固定钮显示「取消固定」、无关闭钮
    await seedThreeTabs();
    useTabsStore.getState().togglePin('/apps');
    // 任务边界（诊断归因：同任务 hover 读陈旧快照）——等 pinned 重渲染落盘（tab 出现取消固定钮）再 hover
    await wait(() => expect(tabEl('/apps')?.querySelector('[aria-label="取消固定"]')).toBeTruthy());
    hoverTab(tabEl('/apps')!);
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('应用结构');
      expect(found).toBeTruthy();
      return found;
    }) as HTMLElement;
    expect(within(card).getByRole('button', { name: '取消固定' })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: '关闭' })).toBeNull();
  });

  it('should_close_preview_card_and_tab_when_close_action_clicked', async () => {
    // AC-13（App 级接线）：点击卡内快捷操作 → 复用关闭回调、卡关闭
    await seedThreeTabs(); // 激活 /users → '/apps' 非活动（关闭不影响激活态）
    hoverTab(tabEl('/apps')!);
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('应用结构');
      expect(found).toBeTruthy();
      return found;
    }) as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: '关闭' }));
    await wait(() => expect(findPreviewCard('应用结构')).toBeNull()); // 点击后卡关闭
    await wait(() => expect(tabEl('/apps')).toBeNull()); // 复用 onClose：页签被关闭
    expect(tabEl('/users')?.getAttribute('aria-selected')).toBe('true'); // 激活态不变
  });

  it('should_not_show_preview_card_when_active_tab_hovered', async () => {
    // AC-14：活动页签 hover 不出现预览卡
    await seedThreeTabs(); // 激活 /users
    hoverTab(tabEl('/users')!);
    await sleep(320);
    expect(findPreviewCard('用户管理')).toBeNull();
  });

  it('should_delay_close_after_pointer_leaves_and_keep_hover_state_out_of_zustand', async () => {
    // AC-15：指针离开页签与卡 → 延迟关闭（防边缘抖动）；hover 候选态不写入 zustand
    await seedThreeTabs();
    const storeListener = vi.fn();
    const unsubscribe = useTabsStore.subscribe(storeListener);
    hoverTab(tabEl('/apps')!);
    await sleep(280);
    const card = await wait(() => {
      const found = findPreviewCard('应用结构');
      expect(found).toBeTruthy();
      return found;
    }) as HTMLElement;
    hoverTab(card); // 指针从页签移入卡：不关闭
    await sleep(120);
    expect(findPreviewCard('应用结构'), '指针在卡上应保持显示').toBeTruthy();
    unhoverTab(tabEl('/apps')!);
    unhoverTab(card);
    expect(findPreviewCard('应用结构'), '离开瞬间不得立即关闭（延迟关闭）').toBeTruthy();
    await sleep(420);
    await wait(() => expect(findPreviewCard('应用结构')).toBeNull());
    expect(storeListener, 'hover 候选态不得写入 zustand').not.toHaveBeenCalled();
    unsubscribe();
  });

  it('should_not_set_native_title_attribute_on_tabs_when_preview_enabled', async () => {
    // AC-16：启用预览卡后页签不再设置原生 title（避免双 tooltip）
    await seedThreeTabs();
    for (const el of tabEls()) {
      expect(el.hasAttribute('title'), `页签 ${el.dataset.tabPath} 不得带原生 title`).toBe(false);
    }
  });
});

// ————— AC-10/11/12/13 组件直测：TabPreviewCard props 契约（变更清单写死的接口） —————
describe('REQ20261120104022 TabPreviewCard 组件直测', () => {
  const fullTitle = '用户管理'.repeat(30); // 完整标题远超 160px 截断宽

  const renderCard = (tab: unknown, handlers: { onRefresh: (p: string) => void; onTogglePin: (p: string) => void; onClose: (p: string) => void }) => {
    render(
      <AntApp>
        <TabPreviewCard
          tab={tab}
          icon={<span data-testid="pv-icon" />}
          label={fullTitle}
          onRefresh={handlers.onRefresh}
          onTogglePin={handlers.onTogglePin}
          onClose={handlers.onClose}
        />
      </AntApp>,
    );
    const leaf = screen.getByText(fullTitle) as HTMLElement;
    return previewCardRoot(leaf);
  };

  it('should_invoke_reused_callbacks_with_tab_path_when_quick_action_clicked', async () => {
    // AC-13（回调契约）：复用 onRefresh/onTogglePin/onClose，收到 tab.path
    await seedTwoTabs();
    const tab = useTabsStore.getState().tabs.find((t) => t.path === '/users')!;
    const onRefresh = vi.fn();
    const onTogglePin = vi.fn();
    const onClose = vi.fn();
    const card = renderCard(tab, { onRefresh, onTogglePin, onClose });
    expect(screen.getByTestId('pv-icon'), '图标槽应渲染 icon ReactNode').toBeTruthy();
    expect(within(card).getByText(fullTitle), '完整标题不受 160px 截断（DOM 全文）').toBeTruthy();
    fireEvent.click(within(card).getByRole('button', { name: '刷新' }));
    fireEvent.click(within(card).getByRole('button', { name: '固定' }));
    fireEvent.click(within(card).getByRole('button', { name: '关闭' }));
    expect(onRefresh).toHaveBeenCalledWith('/users');
    expect(onTogglePin).toHaveBeenCalledWith('/users');
    expect(onClose).toHaveBeenCalledWith('/users');
    // 「点击后卡关闭」是父级（TabBar）职责，App 级用例已覆盖；组件直测不重复断言
  });

  it('should_omit_close_and_pin_actions_when_tab_permanent', async () => {
    // AC-11（组件分支）：permanent → 无关闭钮、无固定钮
    await seedTwoTabs();
    const tab = useTabsStore.getState().tabs[0]!; // 首页常驻签 permanent
    const card = renderCard(tab, { onRefresh: vi.fn(), onTogglePin: vi.fn(), onClose: vi.fn() });
    expect(within(card).getByRole('button', { name: '刷新' })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: '关闭' })).toBeNull();
    expect(within(card).queryByRole('button', { name: '固定' })).toBeNull();
    expect(within(card).queryByRole('button', { name: '取消固定' })).toBeNull();
  });

  it('should_show_unpin_without_close_when_tab_pinned', async () => {
    // AC-12（组件分支）：pinned → 显「取消固定」、无关闭钮
    await seedTwoTabs();
    useTabsStore.getState().togglePin('/users');
    const tab = useTabsStore.getState().tabs.find((t) => t.path === '/users')!;
    const card = renderCard(tab, { onRefresh: vi.fn(), onTogglePin: vi.fn(), onClose: vi.fn() });
    expect(within(card).getByRole('button', { name: '取消固定' })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: '关闭' })).toBeNull();
  });
});

// ————— AC-17/AC-18：溢出翻转 class 写入与 rAF 节流（MutationObserver 观察） —————
describe('REQ20261120104022 AC-17/18 溢出翻转与 rAF 节流', () => {
  it('should_write_class_only_on_boolean_flip_when_scrolling', async () => {
    // AC-17（出现/消失由布尔翻转驱动）＋ AC-18（仅布尔翻转时写 class）
    // 观察域=.tabbar-wrap（bar/箭头/阴影的公共祖先；箭头与阴影是 bar 的兄弟节点，诊断归因）
    await seedThreeTabs();
    const wrap = document.querySelector('.tabbar-wrap') as HTMLElement | null;
    expect(wrap, '页签条缺少 .tabbar-wrap 公共祖先容器').toBeTruthy();
    const bar = tabbar();
    const watcher = watchAttrWrites(wrap!, ['class', 'style', 'disabled']);
    applyGeometry(bar, { scrollWidth: 600, clientWidth: 300, scrollLeft: 0 });
    fireEvent.scroll(bar);
    flushRaf();
    await flushMutations();
    // 阶段 1：溢出入场翻转 → wrap 域出现 class 写入，bar 带溢出态 class
    expect(watcher.records.some((r) => r.attributeName === 'class'), '溢出翻转应在 wrap 域写入 class').toBe(true);
    expect(bar.classList.contains('tabbar-overflowing'), '溢出后 bar 应带 tabbar-overflowing class').toBe(true);

    // 先推进到中段（此步含 canLeft 翻转，允许写入；落定后再做无翻转观察）
    bar.scrollLeft = 150;
    fireEvent.scroll(bar);
    flushRaf();
    await flushMutations();
    expect(isDisabled(arrowEl('right')), '中段右箭头应可用').toBe(false);
    const shadowEls = (): HTMLElement[] =>
      Array.from(wrap!.querySelectorAll<HTMLElement>('[class*="tabbar-shadow"]'));
    const shadowVisibleAtMid = new Map(
      shadowEls().map((el) => [el, el.className.includes('tabbar-shadow-visible')]),
    );

    // 阶段 2：中段内滚动 150→160（canLeft/canRight 均不变）→ 无 class/style 写入（不逐帧写样式）
    watcher.records.length = 0;
    bar.scrollLeft = 160;
    fireEvent.scroll(bar);
    flushRaf();
    await flushMutations();
    expect(
      watcher.records.filter((r) => r.attributeName === 'class' || r.attributeName === 'style'),
      '中段内滚动（无布尔翻转）不得写 class/style',
    ).toHaveLength(0);

    // 阶段 3：滚到最右 300 → canRight 翻转 → 右箭头 disabled 属性翻转＋右阴影失去 tabbar-shadow-visible
    bar.scrollLeft = 300;
    fireEvent.scroll(bar);
    flushRaf();
    await flushMutations();
    expect(isDisabled(arrowEl('right')), '到头翻转应禁用右箭头（disabled 属性翻转）').toBe(true);
    const rightShadowFlip = shadowEls().some(
      (el) => shadowVisibleAtMid.get(el) === true && !el.className.includes('tabbar-shadow-visible'),
    );
    expect(rightShadowFlip, '到头翻转应使右端阴影失去 tabbar-shadow-visible class').toBe(true);
    watcher.disconnect();
  });

  it('should_throttle_dense_scroll_events_via_raf_without_per_frame_style_writes', async () => {
    // AC-18：密集 scroll → 溢出/到头判定经 rAF 节流；挂起帧应被复用/取消（突发后挂起帧 ≤2，
    // 容忍溢出与端点两路独立节流）；判定期间无 style 属性直写
    const bar = await renderOverflowingTabs(150); // 中段起步：入场翻转发生在观察之前
    const watcher = watchAttrWrites(bar);
    rafCbs.clear();
    for (let i = 0; i < 6; i += 1) {
      bar.scrollLeft = 151 + i;
      fireEvent.scroll(bar);
    }
    expect(pendingRafCount(), '密集 scroll 突发应被 rAF 节流（挂起帧 ≤2）').toBeLessThanOrEqual(2);
    flushRaf();
    await flushMutations();
    const attrWrites = watcher.records.filter((r) => r.attributeName === 'class' || r.attributeName === 'style');
    expect(attrWrites, '无翻转的密集滚动不得逐帧写 class/style').toHaveLength(0);
    watcher.disconnect();
  });
});
