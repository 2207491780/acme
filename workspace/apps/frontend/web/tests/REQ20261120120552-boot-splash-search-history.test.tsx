/**
 * REQ-20261120-120552：首屏加载动画（品牌 splash）＋命令面板搜索历史与导航对齐。
 *
 * 覆盖（按 AC 回填用例名）：
 * - AC-2/AC-3 boot.ts 纯函数直测（fake timers＋注入参数，契约 jsdom 断言策略）：最短展示 ≥300ms、
 *   ≤400ms 淡出后从 DOM 移除、data-boot-at 读取、元素缺失静默幂等（ErrorBoundary 兜底清除的机制收敛点）；
 * - AC-1/AC-2/AC-4 index.html 源级锚（轮次 1/2 先例口径，readFileSync 读源码文本）：
 *   #app-boot 全屏遮罩（内联样式＋品牌标 icon.svg＋CSS spinner＋产品名、零外部请求）、
 *   data-boot-at 时间戳写入、内联主题预应用脚本（读 localStorage → data-theme/data-accent 赋值，try/catch 静默）；
 * - AC-6~AC-8 commandHistory.ts 真实 localStorage 读写＋损坏数据注入回退（禁类型断言替代）；
 *   面板历史区渲染/空态「没有搜索历史」/清空钮（仅非空显示）/条目鼠标与键盘执行；
 * - AC-7 执行任一条目（页面/动作）记入历史（kind/key/label/at；去重按 key、最近在前、上限 8、持久化）；
 * - AC-9~AC-12 面板回归（照范本 REQ20261119063129-tabs AC-5 用例口径）：三节过滤与 ↑↓/Enter/Esc 键盘、
 *   「页面」节权限过滤（与侧边导航同源 fail-closed、无 permission 路由恒可见）、
 *   键位提示条「↵ 选择 / ↑↓ 导航 / ESC 关闭」＋placeholder「搜索导航菜单」/「搜索」、
 *   Ctrl+K 打开/autofocus/Esc 关闭/关面板焦点还原（TL 裁决②：Ctrl+K 仅开不关，关闭一律走 Esc）。
 * - AC-5 desktop：无自动化用例（同一 index.html 套壳自动继承；desktop check 门禁＋人工抽验，见契约 AC-5）。
 *
 * 装配照范本 web/tests/REQ20261119063129-tabs.test.tsx：seedSession + useMeStore 播种 + HashRouter 上下文 +
 * <AntApp> 包裹 + matchMedia/ResizeObserver stub + testTimeout ≥15000ms；历史模块用真实函数（无需 mock）。
 * 历史播种走最黑盒通路（TL 裁决①：commandHistory key 与 CommandItem.key 同构 page:<path>/action:<action>，
 * 用例不写死 key 字面量）：先在面板内真实执行一次「发布单」页面命令生成历史，再重开面板断言历史节/回放/清空。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { BOOT_ELEMENT_ID, removeAppBoot } from '../src/boot';
import {
  MAX_HISTORY,
  STORAGE_KEY,
  clearCommandHistory,
  readCommandHistory,
  recordCommandHistory,
} from '../src/components/commandHistory';
import { useAuthStore, useLockStore, useMeStore, useNotifyStore } from '@acme/core';

vi.setConfig({ testTimeout: 30000 });

/**
 * 全套件并行负载下默认 1s 查找窗口偏紧（nav.test 同款放宽口径）：统一 10s 上限的 waitFor。
 */
const wait = (assertion: () => void, timeout = 10000) => waitFor(assertion, { timeout });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

type MenuNode = {
  id: string; parentId: string; menuName: string; menuType: 'DIRECTORY' | 'MENU' | 'BUTTON';
  path: string | null; component: string | null; icon: string | null; sortOrder: number;
  visible: boolean; permissionCode: string | null; status: 'ENABLED' | 'DISABLED';
  children: MenuNode[]; version: string;
};

const menuNode = (over: Partial<MenuNode> = {}): MenuNode => ({
  id: '1', parentId: '0', menuName: '', menuType: 'MENU', path: '/', component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

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

/**
 * App 整棵装配（范本 tabs.test 口径）：播种会话与 me 画像（menus 缺省空数组=静态 NAV 回退），
 * 伴随请求统一回空信封。
 */
const renderAppAt = async (
  hash: string,
  options: { permissions?: string[]; menus?: MenuNode[] } = {},
) => {
  seedSession();
  const { permissions = [], menus = [] } = options;
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) {
      return envelope({ user: meUser, roles: ['super_admin'], permissions, menus });
    }
    return envelope(null); // Dashboard 等伴随请求（nav.test A-25 口径）
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { fetchMock };
};

const tabEl = (path: string): HTMLElement | null =>
  document.querySelector(`.tabbar-tab[data-tab-path="${path}"]`) as HTMLElement | null;

const openPalette = async (): Promise<HTMLElement> => {
  fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
  return screen.findByRole('dialog', {}, { timeout: 10000 });
};

const paletteInput = (dialog: HTMLElement): HTMLInputElement =>
  within(dialog).getByPlaceholderText('搜索导航菜单') as HTMLInputElement;

const activeItemText = (dialog: HTMLElement): string =>
  dialog.querySelector('.command-palette-item[data-active="true"]')?.textContent ?? '';

/**
 * 历史节首条目：「搜索历史」头之后（DOM 序）的第一个命令条目——不依赖节序与 key 形态。
 * 仅在历史非空时调用（空态无条目，首个命中会落到下一节）。
 */
const historyFirstItem = (dialog: HTMLElement): HTMLElement | null => {
  const header = Array.from(dialog.querySelectorAll('*'))
    .find((el) => (el.textContent ?? '').trim() === '搜索历史');
  if (!header) return null;
  const items = Array.from(dialog.querySelectorAll('.command-palette-item')) as HTMLElement[];
  return items.find((item) => header.compareDocumentPosition(item) & Node.DOCUMENT_POSITION_FOLLOWING) ?? null;
};

/** 回首页（观测基准）：回放执行前后的 hash 变化需可观测。 */
const backToHome = async () => {
  window.location.hash = '#/';
  await wait(() => expect(tabEl('/')?.getAttribute('aria-selected')).toBe('true'));
};

/**
 * 最黑盒历史播种（TL 裁决①取向 a）：在面板内真实执行一次「发布单」页面命令生成历史，
 * key 形态由实现侧 recordCommandHistory 决定；mouse=点击执行 / keyboard=Enter 执行。
 * 播种时过滤词 '/releases' 下「发布单」唯一命中（尚无历史条目/无同名页签），可安全 getByText 定位。
 */
const seedHistoryByRealExecution = async (mode: 'mouse' | 'keyboard') => {
  const dialog = await openPalette();
  const input = paletteInput(dialog);
  fireEvent.change(input, { target: { value: '/releases' } });
  await wait(() => expect(within(dialog).getByText('发布单')).toBeTruthy());
  if (mode === 'mouse') {
    fireEvent.click(within(dialog).getByText('发布单'));
  } else {
    fireEvent.keyDown(input, { key: 'Enter' });
  }
  await wait(() => expect(screen.queryByRole('dialog')).toBeNull());
};

// ————— index.html 源级锚素材（被测目标文件：以文本读其源码本身，轮次 1/2 先例口径） —————
const indexHtml = readFileSync(resolve(fileURLToPath(import.meta.url), '../../index.html'), 'utf8');

/** 内联引导脚本集合：内联（无 src）且与 boot 相关（引用 app-boot 或读 localStorage）的 script 块源码拼接。 */
const bootScriptSource = (() => {
  const blocks = indexHtml.match(/<script\b[^>]*>[\s\S]*?<\/script>/g) ?? [];
  return blocks
    .filter((block) => !/\bsrc\s*=/.test(block))
    .filter((block) => block.includes(BOOT_ELEMENT_ID) || block.includes('localStorage'))
    .join('\n');
})();

/** #app-boot 遮罩节点片段：自遮罩起至其后的首个 script（含）之前的标记段。 */
const bootMaskSegment = (): string => {
  const start = indexHtml.indexOf(`id="${BOOT_ELEMENT_ID}"`);
  if (start === -1) return '';
  const scriptStart = indexHtml.indexOf('<script', start);
  return indexHtml.slice(start, scriptStart === -1 ? indexHtml.length : scriptStart);
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
});

// ————— AC-2/AC-3：boot.ts splash 移除纯函数（fake timers＋注入参数直测） —————
describe('REQ20261120120552 AC-2/AC-3 boot.ts splash 移除纯函数', () => {
  const installBootElement = (bootAt: number): HTMLElement => {
    const el = document.createElement('div');
    el.id = BOOT_ELEMENT_ID;
    el.setAttribute('data-boot-at', String(bootAt));
    document.body.appendChild(el);
    return el;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '';
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('should_expose_boot_element_id_constant', () => {
    expect(BOOT_ELEMENT_ID).toBe('app-boot');
  });

  it('should_remove_boot_element_after_fade_when_min_display_elapsed', () => {
    const el = installBootElement(1000);
    // 已展示 600ms ≥ 300ms：立即进入淡出（ErrorBoundary 兜底立即清除的机制口径），≤400ms 过渡后移除
    removeAppBoot({ bootAt: 1000, now: 1600, minDisplayMs: 300, transitionMs: 400, document });
    vi.advanceTimersByTime(0);
    // 淡出 class 已加（契约未定死类名：断言类集合发生变化）
    expect(el.className).not.toBe('');
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBe(el);
    vi.advanceTimersByTime(399);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBe(el); // 过渡期内未移除
    vi.advanceTimersByTime(1);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull(); // 淡出 ≤400ms 完成即从 DOM 移除
  });

  it('should_delay_fade_until_min_display_when_shown_briefly', () => {
    const el = installBootElement(1000);
    // 已展示仅 100ms < 300ms：延迟到足额才淡出（最短展示，避免闪跳）
    removeAppBoot({ bootAt: 1000, now: 1100, minDisplayMs: 300, transitionMs: 400, document });
    vi.advanceTimersByTime(199);
    expect(el.className).toBe(''); // 未足额：不淡出
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBe(el); // 未移除
    vi.advanceTimersByTime(1); // 到达 300ms 足额 → 淡出
    expect(el.className).not.toBe('');
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBe(el);
    vi.advanceTimersByTime(399);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBe(el); // 淡出过渡未满 400ms
    vi.advanceTimersByTime(1); // 淡出 400ms 完成 → 移除（总展示 700ms ≥ 300ms）
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull();
  });

  it('should_read_boot_timestamp_from_data_boot_at_attribute_when_boot_at_omitted', () => {
    const el = installBootElement(Date.now() - 1000);
    // 不显式传 bootAt：从 #app-boot[data-boot-at] 计算已展示时长
    removeAppBoot({ now: Date.now(), minDisplayMs: 300, transitionMs: 400, document });
    vi.advanceTimersByTime(0);
    expect(el.className).not.toBe(''); // 已展示 1000ms ≥ 300ms → 立即淡出
    vi.advanceTimersByTime(400);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull();
  });

  it('should_remove_with_defaults_when_called_without_options', () => {
    installBootElement(Date.now() - 1000);
    // 生产路径（main.tsx 首帧后调用）：无参调用，默认 minDisplayMs=300 / transitionMs=400
    expect(() => removeAppBoot()).not.toThrow();
    vi.advanceTimersByTime(400);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull();
  });

  it('should_be_silent_when_boot_element_missing', () => {
    // 元素不存在：静默幂等（不抛错、无定时器副作用）
    expect(() => removeAppBoot({ document })).not.toThrow();
    expect(() => removeAppBoot({ bootAt: 0, now: 10_000, minDisplayMs: 300, transitionMs: 400, document })).not.toThrow();
    vi.advanceTimersByTime(60_000);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull();
  });

  it('should_not_break_when_remove_app_boot_called_twice', () => {
    installBootElement(1000);
    removeAppBoot({ bootAt: 1000, now: 2000, minDisplayMs: 300, transitionMs: 400, document });
    // 重复调用（如 ErrorBoundary 与首帧路径叠加）：幂等不抛错、最终仍移除不残留
    expect(() => removeAppBoot({ bootAt: 1000, now: 2000, minDisplayMs: 300, transitionMs: 400, document })).not.toThrow();
    vi.advanceTimersByTime(400);
    expect(document.getElementById(BOOT_ELEMENT_ID)).toBeNull();
  });
});

// ————— AC-1/AC-2/AC-4：index.html 源级锚（内联零依赖 splash＋主题预应用） —————
describe('REQ20261120120552 AC-1/AC-2/AC-4 index.html 源级锚', () => {
  it('should_render_inline_full_screen_boot_mask_when_html_arrives', () => {
    // 遮罩为 index.html 静态内联：内联样式＋#app-boot 节点＋全屏定位
    expect(indexHtml).toContain('<style');
    expect(indexHtml).toContain(`id="${BOOT_ELEMENT_ID}"`);
    expect(indexHtml).toMatch(/position\s*:\s*fixed/);
    // CSS spinner（品牌标＋spinner＋产品名三要素之动效锚）
    expect(indexHtml).toMatch(/@keyframes/);
    expect(indexHtml).toMatch(/animation\s*:/);
    // 品牌标＋产品名（产品名字面量契约未定死：锚页面品牌文本 ACME/多端前端交付台）
    const mask = bootMaskSegment();
    expect(mask).toContain('icon.svg');
    expect(mask).toMatch(/ACME|多端前端交付台/);
    // 无外部请求依赖：无 http(s) 外链资源
    expect(indexHtml).not.toMatch(/(?:src|href)\s*=\s*["']https?:\/\//);
  });

  it('should_stamp_boot_timestamp_on_boot_element_when_html_boots', () => {
    // 引导脚本记录 boot 时间戳到 #app-boot[data-boot-at]
    expect(indexHtml).toMatch(/data-boot-at/);
    expect(bootScriptSource.length).toBeGreaterThan(0);
    expect(bootScriptSource).toContain('data-boot-at');
    expect(bootScriptSource).toMatch(/Date\.now|performance\.now/);
  });

  it('should_pre_apply_persisted_theme_before_first_paint_when_inline_script_runs', () => {
    // 内联（非外链）引导脚本：首绘前读持久化设置应用 data-theme/data-accent，失败 try/catch 静默
    expect(bootScriptSource).toBeTruthy();
    expect(bootScriptSource).toContain('localStorage');
    expect(bootScriptSource).toMatch(/data-theme|dataset\.theme/);
    expect(bootScriptSource).toMatch(/data-accent|dataset\.accent/);
    expect(bootScriptSource).toMatch(/try\s*\{/);
    expect(bootScriptSource).toMatch(/catch/);
  });
});

// ————— AC-6~AC-8：commandHistory.ts 历史模块（真实 localStorage 读写＋损坏注入回退） —————
describe('REQ20261120120552 AC-6~AC-8 commandHistory.ts 历史模块', () => {
  it('should_expose_command_history_storage_contract', () => {
    expect(MAX_HISTORY).toBe(8);
    expect(STORAGE_KEY).toBe('acme.commandHistory.v1');
  });

  it('should_fallback_to_empty_when_storage_corrupted', () => {
    // 损坏 JSON / 非数组 / 标量：异常回退 []（运行时校验，禁类型断言替代）
    window.localStorage.setItem(STORAGE_KEY, '{{not-json');
    expect(readCommandHistory()).toEqual([]);
    window.localStorage.setItem(STORAGE_KEY, '{"not":"an-array"}');
    expect(readCommandHistory()).toEqual([]);
    window.localStorage.setItem(STORAGE_KEY, '"just-a-string"');
    expect(readCommandHistory()).toEqual([]);
  });

  it('should_filter_invalid_entries_when_reading_history', () => {
    // 非法条目过滤（kind 非法/缺字段/类型错误/非对象），合法条目保持存储顺序
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify([
      { kind: 'page', key: '/ok-1', label: '有效一', at: 1 },
      { kind: 'teleport', key: '/bad-kind', label: '非法kind', at: 2 },
      { key: '/missing-kind', label: '缺kind', at: 3 },
      { kind: 'action', label: '缺key', at: 4 },
      { kind: 'page', key: '/missing-label', at: 5 },
      { kind: 'page', key: '/bad-at', label: '缺at' },
      { kind: 'page', key: '/str-at', label: '字符串at', at: 'x' },
      { kind: 'page', key: '/null-at', label: '空at', at: null },
      'junk-string',
      42,
      null,
      { kind: 'page', key: '/ok-2', label: '有效二', at: 6 },
    ]));
    expect(readCommandHistory().map((entry) => [entry.kind, entry.key, entry.label, entry.at])).toEqual([
      ['page', '/ok-1', '有效一', 1],
      ['page', '/ok-2', '有效二', 6],
    ]);
  });

  it('should_dedupe_move_front_and_cap_history_when_recording', () => {
    for (let i = 1; i <= 8; i += 1) {
      recordCommandHistory({ kind: 'page', key: `/p-${i}`, label: `页面${i}` });
    }
    // 最近在前
    expect(readCommandHistory().map((entry) => entry.key))
      .toEqual(['/p-8', '/p-7', '/p-6', '/p-5', '/p-4', '/p-3', '/p-2', '/p-1']);

    // 第 9 条：置顶并截断最旧（上限 MAX_HISTORY=8），at 为数值时间戳
    const afterNinth = recordCommandHistory({ kind: 'action', key: 'lock-screen', label: '锁定屏幕' });
    expect(afterNinth).toHaveLength(MAX_HISTORY);
    expect(afterNinth[0]).toMatchObject({ kind: 'action', key: 'lock-screen', label: '锁定屏幕' });
    expect(afterNinth.map((entry) => entry.key)).not.toContain('/p-1');
    expect(afterNinth.every((entry) => typeof entry.at === 'number')).toBe(true);

    // 去重按 key：重复 key 更新并置顶，不产生第二条
    const deduped = recordCommandHistory({ kind: 'page', key: '/p-4', label: '页面四新标签' });
    expect(deduped).toHaveLength(MAX_HISTORY);
    expect(deduped[0]).toMatchObject({ kind: 'page', key: '/p-4', label: '页面四新标签' });
    expect(deduped.filter((entry) => entry.key === '/p-4')).toHaveLength(1);

    // localStorage 持久化写回（STORAGE_KEY）
    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).toBeTruthy();
    expect(JSON.parse(raw!)).toEqual(deduped);
  });

  it('should_clear_history_and_storage_when_clear_called', () => {
    recordCommandHistory({ kind: 'page', key: '/only', label: '唯一' });
    expect(readCommandHistory()).toHaveLength(1);
    clearCommandHistory();
    expect(readCommandHistory()).toEqual([]);
    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw === null || (JSON.parse(raw) as unknown[]).length === 0).toBe(true);
  });
});

// ————— AC-6：面板「搜索历史」区（空态/历史条目渲染） —————
describe('REQ20261120120552 AC-6 命令面板搜索历史区', () => {
  it('should_show_no_history_empty_state_when_history_empty', async () => {
    await renderAppAt('#/', { permissions: [] });
    const dialog = await openPalette();

    // 空查询：历史区头部＋「没有搜索历史」空态（对齐截图）
    expect(within(dialog).getByText('搜索历史')).toBeTruthy();
    expect(within(dialog).getByText('没有搜索历史')).toBeTruthy();
    // 清空钮仅非空显示
    expect(within(dialog).queryByRole('button', { name: /清空/ })).toBeNull();
    expect(within(dialog).queryByText(/清空/)).toBeNull();
  });

  it('should_show_history_entries_and_clear_button_when_history_exists', async () => {
    await renderAppAt('#/', { permissions: [] });
    // 播种＝面板内真实执行「发布单」页面命令生成历史（不写死 key 字面量）
    await seedHistoryByRealExecution('keyboard');
    const dialog = await openPalette();

    // 历史节渲染：头部在；空态消失（AC-6 空态文案仅无历史时出现）＋清空钮仅非空显示
    expect(within(dialog).getByText('搜索历史')).toBeTruthy();
    await wait(() => {
      expect(within(dialog).queryByText('没有搜索历史')).toBeNull();
      expect(within(dialog).getByRole('button', { name: /清空/ })).toBeTruthy();
    });
  });

  it('should_execute_history_entry_by_mouse_and_record', async () => {
    await renderAppAt('#/', { permissions: [] });
    // 播种＝鼠标真实执行「发布单」生成历史（不写死 key 字面量）
    await seedHistoryByRealExecution('mouse');
    await backToHome();

    // 重开面板：鼠标点击历史节首条目回放执行（页面=打开为页签）
    const dialog = await openPalette();
    const entry = historyFirstItem(dialog);
    expect(entry?.textContent).toContain('发布单');
    fireEvent.click(entry!);
    await wait(() => {
      expect(window.location.hash).toBe('#/releases');
      expect(tabEl('/releases')).toBeTruthy();
    });
    await wait(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 回放执行记入历史：同一命令两次执行，按 key 去重仍单条、最近在前（断言不写死 key 字面量）
    await wait(() => {
      const history = readCommandHistory();
      expect(history.filter((item) => item.kind === 'page' && item.label === '发布单')).toHaveLength(1);
      expect(history[0]?.kind).toBe('page');
    });
  });

  it('should_execute_history_entry_by_keyboard_and_record', async () => {
    await renderAppAt('#/', { permissions: [] });
    // 播种＝键盘真实执行「发布单」生成历史（不写死 key 字面量）
    await seedHistoryByRealExecution('keyboard');
    await backToHome();

    // 重开面板：键盘选中历史节首条目回放执行（按元素身份定位，契约未定死节序/初始选中项）
    const dialog = await openPalette();
    const input = paletteInput(dialog);
    const target = historyFirstItem(dialog);
    expect(target?.textContent).toContain('发布单');
    let active: Element | null = null;
    for (let i = 0; i < 25; i += 1) {
      active = dialog.querySelector('.command-palette-item[data-active="true"]');
      if (active === target) break;
      fireEvent.keyDown(input, { key: 'ArrowDown' });
    }
    expect(active).toBe(target);
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => {
      expect(window.location.hash).toBe('#/releases');
      expect(tabEl('/releases')).toBeTruthy();
    });
    await wait(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 回放执行记入历史：同一命令两次执行，按 key 去重仍单条、最近在前（断言不写死 key 字面量）
    await wait(() => {
      const history = readCommandHistory();
      expect(history.filter((entry) => entry.kind === 'page' && entry.label === '发布单')).toHaveLength(1);
      expect(history[0]?.kind).toBe('page');
    });
  });
});

// ————— AC-7/AC-8：面板执行记入历史＋清空（UI 层） —————
describe('REQ20261120120552 AC-7/AC-8 面板执行记录与清空', () => {
  it('should_record_page_command_into_history_when_executed', async () => {
    await renderAppAt('#/', { permissions: [] });
    const dialog = await openPalette();
    const input = paletteInput(dialog);

    fireEvent.change(input, { target: { value: '/releases' } });
    await wait(() => expect(within(dialog).getByText('发布单')).toBeTruthy());
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => expect(window.location.hash).toBe('#/releases'));

    // 执行页面条目 → 记入历史（TL 裁决①：key 与 CommandItem.key 同构，断言放宽为 kind＋label＋key 含路径）
    await wait(() => {
      const entry = readCommandHistory().find((e) => e.kind === 'page' && e.key.includes('/releases'));
      expect(entry).toBeTruthy();
      expect(entry?.label).toBe('发布单');
      expect(typeof entry?.at).toBe('number');
    });
    // localStorage 持久化（STORAGE_KEY）
    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).toBeTruthy();
    const persisted = JSON.parse(raw!) as { kind: string; label: string; key: string }[];
    expect(persisted.some((item) => item.kind === 'page' && item.label === '发布单' && item.key.includes('/releases'))).toBe(true);
  });

  it('should_record_action_command_into_history_when_executed', async () => {
    await renderAppAt('#/', { permissions: [] });
    const dialog = await openPalette();
    const input = paletteInput(dialog);

    fireEvent.change(input, { target: { value: '锁定' } });
    await wait(() => expect(within(dialog).getByText('锁定屏幕')).toBeTruthy());
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => expect(useLockStore.getState().locked).toBe(true));

    // 执行动作条目 → 记入历史（kind='action'，label 规范文案，key 非空）
    await wait(() => {
      const actionEntry = readCommandHistory().find((entry) => entry.kind === 'action');
      expect(actionEntry).toBeTruthy();
      expect(actionEntry?.label).toBe('锁定屏幕');
      expect(typeof actionEntry?.key).toBe('string');
      expect((actionEntry?.key ?? '').length).toBeGreaterThan(0);
    });
    useLockStore.setState({ locked: false, passcode: '' }); // 防锁屏覆盖层跨用例残留（范本口径）
  });

  it('should_clear_history_and_return_to_empty_state_when_clear_clicked', async () => {
    await renderAppAt('#/', { permissions: [] });
    // 播种＝面板内真实执行「发布单」生成历史（不写死 key 字面量）
    await seedHistoryByRealExecution('keyboard');
    const dialog = await openPalette();
    expect(within(dialog).queryByText('没有搜索历史')).toBeNull(); // 历史非空

    // 点击清空：立即清空（含存储）并回到「没有搜索历史」空态
    fireEvent.click(within(dialog).getByRole('button', { name: /清空/ }));
    await wait(() => expect(within(dialog).getByText('没有搜索历史')).toBeTruthy());
    await wait(() => expect(within(dialog).queryByRole('button', { name: /清空/ })).toBeNull());
    expect(readCommandHistory()).toEqual([]);
    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw === null || (JSON.parse(raw) as unknown[]).length === 0).toBe(true);
  });
});

// ————— AC-9/AC-10/AC-11/AC-12：面板回归（三节键盘/权限过滤/文案/焦点） —————
describe('REQ20261120120552 AC-9~AC-12 面板回归', () => {
  it('should_keep_three_section_filter_and_keyboard_regression_when_querying', async () => {
    await renderAppAt('#/', { permissions: [] });
    const dialog = await openPalette();
    const input = paletteInput(dialog);

    // 三节仍在＋键盘导航不变（范本 AC-5 口径回归）
    expect(within(dialog).getByText('页面')).toBeTruthy();
    expect(within(dialog).getByText('页签')).toBeTruthy();
    expect(within(dialog).getByText('快捷操作')).toBeTruthy();
    expect(within(dialog).getByText('没有搜索历史')).toBeTruthy();
    expect(activeItemText(dialog)).toContain('概览');
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(activeItemText(dialog)).toContain('发布单');

    // 非空查询：历史区不渲染（仅空查询显示）；三节过滤照旧
    fireEvent.change(input, { target: { value: '/releases' } });
    await wait(() => expect(within(dialog).getByText('发布单')).toBeTruthy());
    expect(within(dialog).queryByText('搜索历史')).toBeNull();
    expect(within(dialog).queryByText('没有搜索历史')).toBeNull();
    fireEvent.keyDown(input, { key: 'Enter' });
    await wait(() => expect(window.location.hash).toBe('#/releases'));
    await wait(() => expect(screen.queryByRole('dialog')).toBeNull());

    // Esc 关闭回归：重开面板输入动作关键词
    const dialog2 = await openPalette();
    const input2 = paletteInput(dialog2);
    fireEvent.change(input2, { target: { value: '锁定' } });
    await wait(() => expect(within(dialog2).getByText('锁定屏幕')).toBeTruthy());
    fireEvent.keyDown(input2, { key: 'Escape', keyCode: 27 }); // rc-dialog 按 keyCode===27 判定（范本口径）
    await wait(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('should_hide_permission_gated_pages_when_user_lacks_permission', async () => {
    const menus: MenuNode[] = [
      menuNode({ id: '1', menuName: '首页面板', menuType: 'MENU', path: '/', permissionCode: null }),
      menuNode({ id: '2', menuName: '用户管理', menuType: 'MENU', path: '/users', permissionCode: 'system:user:list' }),
    ];
    await renderAppAt('#/', { permissions: [], menus });
    const dialog = await openPalette();

    // 无 permission 的路由恒可见（fail-closed 不误伤）
    await wait(() => expect(within(dialog).getByText('首页面板')).toBeTruthy());
    // 无权限项不出现（与侧边导航权限过滤同源）
    expect(within(dialog).queryByText('用户管理')).toBeNull();
  });

  it('should_show_permission_gated_pages_when_user_has_permission', async () => {
    const menus: MenuNode[] = [
      menuNode({ id: '1', menuName: '首页面板', menuType: 'MENU', path: '/', permissionCode: null }),
      menuNode({ id: '2', menuName: '用户管理', menuType: 'MENU', path: '/users', permissionCode: 'system:user:list' }),
    ];
    await renderAppAt('#/', { permissions: ['system:user:list'], menus });
    const dialog = await openPalette();

    // 有权限项完整呈现
    await wait(() => {
      expect(within(dialog).getByText('用户管理')).toBeTruthy();
      expect(within(dialog).getByText('首页面板')).toBeTruthy();
    });
  });

  it('should_render_hints_bar_and_updated_placeholders_when_palette_open', async () => {
    await renderAppAt('#/', { permissions: [] });

    // Header 搜索框 placeholder 更新为「搜索」；旧语义文案废除
    expect(screen.getByPlaceholderText('搜索')).toBeTruthy();
    expect(screen.queryByPlaceholderText('搜索页面 / 页签 / 快捷操作…')).toBeNull();

    const dialog = await openPalette();
    // 面板 placeholder「搜索导航菜单」
    expect(within(dialog).getByPlaceholderText('搜索导航菜单')).toBeTruthy();
    // 底部键位提示条「↵ 选择 / ↑↓ 导航 / ESC 关闭」（压缩空白后匹配，兼容 kbd/span 分片渲染）
    const hints = (dialog.textContent ?? '').replace(/\s+/g, '');
    expect(hints).toContain('↵选择');
    expect(hints).toContain('↑↓导航');
    expect(hints).toContain('ESC关闭');
  });

  it('should_toggle_autofocus_and_restore_focus_on_palette_close', async () => {
    await renderAppAt('#/', { permissions: [] });
    const headerSearch = screen.getByPlaceholderText('搜索') as HTMLInputElement;
    headerSearch.focus();

    // Ctrl+K 打开＋autofocus（TL 裁决②：Ctrl+K 仅开不关，App 无 toggle）
    fireEvent.keyDown(window, { ctrlKey: true, key: 'k' });
    const dialog = await screen.findByRole('dialog', {}, { timeout: 10000 });
    const input = paletteInput(dialog);
    await wait(() => expect(document.activeElement).toBe(input));

    // Esc 关闭（照 tabs.test 口径 keyDown Escape）→ 焦点还原到打开前聚焦的 Header 搜索框
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 27 });
    await wait(() => expect(screen.queryByRole('dialog')).toBeNull());
    await wait(() => expect(document.activeElement).toBe(headerSearch));
  });
});
