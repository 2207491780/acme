/**
 * REQ-20261122-233427（THEME-4）AC-6 顶栏 widget 开关 + AC-7 偏好抽屉「布局」分组：web 组件用例。
 *
 * 表面 = 契约确认记录②⑤⑥ + AC-6/AC-7 + 委派单钉死事实：
 *   - topbarWidgets=个人偏好级（Record<WidgetKey,boolean>，WidgetKey 枚举=契约②原文
 *     search/bell/lang/timezone/appearance/themeToggle/lock/fullscreen/settings（release 键已移除 UI 呈现，legacy 容忍），
 *     缺省全显=现状逐字），与租户级水印/页脚控制（systemConfigStore）正交；
 *   - Header 按偏好条件渲染；重置=恢复缺省全显；
 *   - 偏好抽屉增「布局」分组：layoutMode/tabStyle/siderTone/topbarWidgets 开关组四控件，双语文案。
 *
 * 装配照范本 web/tests/REQ20261121043502-appearanceDrawer.test.tsx（整棵 App + AntApp +
 * HashRouter + ThemedApp 复刻 buildTheme 消费点 + 会话/me 播种 + 浏览器桩 + Popconfirm 重置口径）。
 *
 * 交互口径（pitfalls #7/#8/#9/#10/#14 + THEME-1/2 先例）：
 *   - Segmented 以「选项集」联合锚定分组（防多组并存跨组误命中）；开关按祖先链最近命中标签定位；
 *   - widget 顶栏定位=aria-label/title/文本/placeholder 宽容正则（文案契约未钉死）；
 *   - 抽屉内 widget 开关组以「开关行标签能命中的 WidgetKey 并集 ≥ 8/9」断言齐备
 *     （appearance/themeToggle 等行文案若与既有「外观」分组文案撞车，阈值容忍 1 键缺位）。
 *
 * 锚点校准注记（实现交付后自验轮收敛）：widget 按钮的可访问名/文案、抽屉分组标签均为
 * 契约未钉死面，正则按背景节顶栏 widget 清单宽出口径编写。
 */
import { act } from 'react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import { buildTheme } from '../src/theme';
import {
  DEFAULT_PREFERENCES,
  i18n,
  useAuthStore,
  useLockStore,
  useMeStore,
  useNotifyStore,
  useSystemConfigStore,
  type Settings,
} from '@acme/core';

vi.setConfig({ testTimeout: 45000 });
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const BLOB_KEY = 'acme.frontend.ops.v1'; // 契约钉死（packages/core/src/constants.ts）

// ————— WidgetKey 探针（契约②原文枚举；正则=背景节顶栏 widget 清单宽出、双语文案） —————

type WidgetProbe = { key: string; header: RegExp; row: RegExp };

/** header=顶栏显隐定位面；row=抽屉开关行标签面（appearance/settings 行文案带入口后缀防与既有「外观」分组撞车）。 */
const WIDGET_PROBES: WidgetProbe[] = [
  { key: 'search', header: /搜索|search/i, row: /搜索|search/i },
  { key: 'bell', header: /通知|消息|notification|bell/i, row: /通知|notification|bell/i },
  { key: 'lang', header: /语言|language/i, row: /语言|language/i },
  { key: 'timezone', header: /时区|time.?zone/i, row: /时区|time.?zone/i },
  {
    key: 'appearance',
    header: /偏好|外观|preference|appearance/i,
    row: /偏好(抽屉|入口|按钮)|外观(按钮|入口|开关)|appearance (entry|button|drawer)|preferences? (entry|button|drawer)/i,
  },
  { key: 'themeToggle', header: /明暗|暗色|亮色|主题|theme|dark|light/i, row: /明暗|主题切换|theme toggle|dark mode|暗色|亮色/i },
  { key: 'lock', header: /锁屏|锁定|lock/i, row: /锁屏|lock/i },
  { key: 'fullscreen', header: /全屏|full\s?screen|fullscreen/i, row: /全屏|full\s?screen|fullscreen/i },
  { key: 'settings', header: /(^|\s)(设置|Settings)(\s|$)/i, row: /设置(入口|按钮)|settings (entry|button)/i },
];

/**
 * Header 可见性探针：search 顶栏搜索框随 REQ-20261126-125836 ⑦ 移除（偏好开关仍保留），
 * 因此 header 侧断言不含 search；抽屉开关组仍覆盖全部 9 键。
 */
const HEADER_WIDGET_PROBES = WIDGET_PROBES.filter((p) => p.key !== 'search');

const widgetProbe = (key: string): WidgetProbe => {
  const probe = WIDGET_PROBES.find((p) => p.key === key);
  expect(probe, `WidgetKey ${key} 应在契约②枚举探针表内`).toBeTruthy();
  return probe as WidgetProbe;
};

/** topbarWidgets 缺省全显（=现状逐字）。 */
const allWidgetsVisible = (): Record<string, boolean> =>
  Object.fromEntries(WIDGET_PROBES.map((p) => [p.key, true]));

// ————— 浏览器桩 —————

const stubBrowserGaps = (): void => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('min-width'),
    media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

// ————— App 壳装配（appearanceDrawer 范本） —————

const envelope = (data: unknown, status = 200): Response => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const mePayload = (permissions: string[]) => ({
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions,
  menus: [],
});

/** 复刻 main.tsx 消费点：ConfigProvider theme=buildTheme(settings)。 */
const ThemedApp = (): ReactNode => {
  const settings = useStore((s) => s.state.settings) as unknown as Settings;
  return (
    <ConfigProvider theme={buildTheme(settings)}>
      <App />
    </ConfigProvider>
  );
};

const renderApp = async (): Promise<void> => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.includes('/system/me')) return envelope(mePayload(['system:user:list', 'system:message:list']));
    return envelope(null);
  }));
  await useMeStore.getState().load();
  window.location.hash = '#/';
  render(<AntApp><HashRouter><ThemedApp /></HashRouter></AntApp>);
  await screen.findByRole('button', { name: /^(设置|Settings)$/ });
};

// ————— settings / blob —————

type SettingsLike = { schemaVersion?: number; preferences?: Record<string, unknown> };

const currentPrefsLoose = (): Record<string, unknown> => {
  const prefs = (useStore.getState().state.settings as unknown as SettingsLike).preferences;
  expect(prefs, 'settings 应内嵌 preferences 对象').toBeTruthy();
  return prefs as Record<string, unknown>;
};

const setPrefs = (over: Record<string, unknown>): void => {
  useStore.getState().dispatch({
    type: 'UPDATE_SETTINGS',
    patch: { preferences: { ...DEFAULT_PREFERENCES, ...over } as unknown as Settings['preferences'] },
  });
};

const resetSettingsBaseline = (): void => {
  const store = useStore.getState();
  if (typeof store.resetData === 'function') store.resetData();
  store.dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
};

const blobPrefs = (): Record<string, unknown> | null => {
  const raw = window.localStorage.getItem(BLOB_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const holder = (parsed['state'] && typeof parsed['state'] === 'object'
      ? parsed['state'] : parsed) as Record<string, unknown>;
    const settings = holder['settings'] as Record<string, unknown> | undefined;
    return (settings?.['preferences'] as Record<string, unknown> | undefined) ?? null;
  } catch {
    return null;
  }
};

// ————— 顶栏 / 抽屉定位（appearanceDrawer 范本） —————

const headerEl = (): HTMLElement => {
  const header = document.querySelector('header.ant-layout-header');
  expect(header, '顶栏 header.ant-layout-header 应渲染').toBeTruthy();
  return header as HTMLElement;
};

const headerControls = (): HTMLElement[] =>
  Array.from(headerEl().querySelectorAll<HTMLElement>(
    'button, input, [role="button"], [class*="notif"], [class*="bell"], [class*="badge"]',
  ));

const findWidgetInHeader = (probe: RegExp): HTMLElement | null =>
  headerControls().find((el) => {
    const meta = `${el.getAttribute('aria-label') ?? ''} ${el.getAttribute('title') ?? ''}`
      + ` ${el.textContent ?? ''} ${(el as HTMLInputElement).placeholder ?? ''} ${el.className}`;
    return probe.test(meta);
  }) ?? null;

const preferenceEntry = (): HTMLElement => {
  const entry = findWidgetInHeader(/偏好|外观|preference/i);
  expect(entry, '顶栏应存在偏好入口按钮').toBeTruthy();
  return entry as HTMLElement;
};

const openPreferencesDrawer = async (): Promise<HTMLElement> => {
  await act(async () => {
    fireEvent.click(preferenceEntry());
  });
  await waitFor(() => expect(document.querySelector('.ant-drawer-open')).toBeTruthy());
  return document.querySelector('.ant-drawer-open') as HTMLElement;
};

// ————— Segmented 分组锚定与选项点击（appearanceDrawer 范本 + 选项集联合锚定） —————

const optionMetasOf = (group: HTMLElement): string[] =>
  Array.from(group.querySelectorAll<HTMLElement>('.ant-segmented-item')).map((item) => [
    item.textContent ?? '',
    item.getAttribute('title') ?? '',
    item.querySelector('.ant-segmented-item-label')?.getAttribute('title') ?? '',
    ...Array.from(item.querySelectorAll('[aria-label]')).map((el) => el.getAttribute('aria-label') ?? ''),
  ].join(' '));

/**
 * 以「选项集」锚定 Segmented 分组（F1 同款）：expectedCount 可选——按选项数判别
 * （siderTone 两选项 vs 主题模式三选项 auto/dark 文案撞车，防跨组误命中致假通过）。
 */
const findSegmentedByOptions = (scope: HTMLElement, optionMatchers: RegExp[], expectedCount?: number): HTMLElement => {
  const groups = Array.from(scope.querySelectorAll<HTMLElement>('.ant-segmented'));
  for (const group of groups) {
    const metas = optionMetasOf(group);
    if (typeof expectedCount === 'number'
      && group.querySelectorAll('.ant-segmented-item').length !== expectedCount) continue;
    if (optionMatchers.every((re) => metas.some((m) => re.test(m)))) return group;
  }
  expect.fail(
    `抽屉内未找到含选项 ${optionMatchers.map(String).join(' & ')}`
    + `${expectedCount ? `（且恰 ${expectedCount} 个选项）` : ''} 的 Segmented 分组`
    + `（实际 ${groups.length} 组，meta：${groups.map((g) => `${optionMetasOf(g).length}项:${optionMetasOf(g).join('｜')}`).join(' / ')}）`,
  );
};

/** 开关按祖先链最近命中标签定位（appearanceDrawer 范本 labeledSwitch 口径）。 */
const labeledSwitch = (scope: HTMLElement, label: RegExp): HTMLElement | null => {
  let best: { el: HTMLElement; depth: number } | null = null;
  for (const sw of within(scope).queryAllByRole('switch')) {
    let node: HTMLElement | null = sw as HTMLElement;
    for (let depth = 0; depth < 5 && node; depth += 1) {
      if (label.test(node.textContent ?? '')) {
        if (!best || depth < best.depth) best = { el: sw as HTMLElement, depth };
        break;
      }
      node = node.parentElement;
    }
  }
  return best?.el ?? null;
};

/**
 * 抽屉内开关行标签能命中的 WidgetKey 并集（AC-7 齐备面）：每个 switch 向上找最近含
 * row 探针文案的祖先，命中即计入该键（每 switch 首个命中深度即停，防全树文本误吸全键）。
 */
const drawerWidgetKeyUnion = (drawer: HTMLElement): { keys: Set<string>; debug: string } => {
  const keys = new Set<string>();
  const rows: string[] = [];
  for (const sw of within(drawer).queryAllByRole('switch')) {
    let node: HTMLElement | null = sw as HTMLElement;
    for (let depth = 0; depth < 6 && node; depth += 1) {
      const text = (node.textContent ?? '').trim();
      const hit = WIDGET_PROBES.filter((probe) => probe.row.test(text)).map((p) => p.key);
      if (hit.length > 0) {
        for (const key of hit) keys.add(key);
        rows.push(`switch#${rows.length + 1}@depth${depth}→[${hit.join(',')}]«${text.slice(0, 30)}»`);
        break;
      }
      node = node.parentElement;
    }
  }
  return { keys, debug: rows.join(' | ') || '(无 switch 行命中任何 row 探针)' };
};

// ————— i18n 裸 key 扫描（THEME-1 口径） —————

const RAW_KEY_RE = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$/;
const rawKeyTexts = (scope: HTMLElement): string[] =>
  Array.from(scope.querySelectorAll('*'))
    .map((el) => (el.childElementCount === 0 ? (el.textContent ?? '').trim() : ''))
    .filter((text) => RAW_KEY_RE.test(text));

// ————— 租户级正交面（AC-6） —————

const tenantConfigSnapshot = (): string => JSON.stringify(useSystemConfigStore.getState());

// ————— 用例 —————

beforeEach(async () => {
  stubBrowserGaps();
  await act(async () => {
    await i18n.changeLanguage('zh-CN');
  });
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  resetSettingsBaseline();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useLockStore.setState({ locked: false, passcode: '' });
  useNotifyStore.setState({ unreadCount: 0 });
  resetSettingsBaseline();
  await act(async () => {
    await i18n.changeLanguage('zh-CN');
  });
});

describe('REQ20261122233427 顶栏 widget 开关显隐与持久化（AC-6）', () => {
  it('should_hide_and_show_header_widgets_and_persist_when_widget_switch_operated', async () => {
    await renderApp();

    // 基线正控：缺省全显=现状——除已移除的 Header 搜索框外，8 个 widget 顶栏探针可定位（失败时 dump 顶栏控件面校准）
    const headerDump = (): string => headerControls()
      .map((el) => `${el.tagName}[${(el.className || '').toString().slice(0, 40)}]|${el.getAttribute('aria-label') ?? ''}|${(el.textContent ?? '').slice(0, 12)}`)
      .join(' ; ');
    for (const probe of HEADER_WIDGET_PROBES) {
      expect(
        findWidgetInHeader(probe.header),
        `缺省顶栏应可见 widget ${probe.key}（探针 ${probe.header}）——顶栏控件面：${headerDump()}`,
      ).toBeTruthy();
    }

    // 关 search/lock/fullscreen 三键：prefs + 顶栏显隐 + blob 持久化
    const drawer = await openPreferencesDrawer();
    for (const key of ['search', 'lock', 'fullscreen']) {
      const sw = labeledSwitch(drawer, widgetProbe(key).row);
      expect(sw, `抽屉内应存在 ${key} 开关（行探针 ${widgetProbe(key).row}）`).toBeTruthy();
      await act(async () => {
        fireEvent.click(sw as HTMLElement);
      });
      await waitFor(() => {
        const widgets = currentPrefsLoose()['topbarWidgets'] as Record<string, unknown> | undefined;
        expect(widgets?.[key], `关闭 ${key} 后 prefs.topbarWidgets 应同步`).toBe(false);
      });
    }
    await waitFor(() => {
      const widgets = (blobPrefs()?.['topbarWidgets'] ?? {}) as Record<string, unknown>;
      expect(widgets['search']).toBe(false);
      expect(widgets['lock']).toBe(false);
      expect(widgets['fullscreen']).toBe(false);
    });

    // 顶栏显隐面：三键消失、其余键不受牵连
    await waitFor(() => {
      for (const key of ['search', 'lock', 'fullscreen']) {
        expect(findWidgetInHeader(widgetProbe(key).header), `关闭后顶栏不应再渲染 ${key}`).toBeNull();
      }
      for (const key of ['bell', 'lang', 'timezone', 'appearance', 'themeToggle', 'settings']) {
        expect(findWidgetInHeader(widgetProbe(key).header), `未关闭键 ${key} 顶栏应保持可见`).toBeTruthy();
      }
    });

    // 重开 search：显隐回正 + prefs 同步
    const reopenSwitch = labeledSwitch(drawer, widgetProbe('search').row);
    expect(reopenSwitch, 'search 开关应仍可定位').toBeTruthy();
    await act(async () => {
      fireEvent.click(reopenSwitch as HTMLElement);
    });
    await waitFor(() => {
      expect((currentPrefsLoose()['topbarWidgets'] as Record<string, unknown>)['search']).toBe(true);
      // ⑦：Header 搜索框已删除，search 偏好仅保留在偏好面，不回到顶栏
      expect(findWidgetInHeader(widgetProbe('search').header)).toBeNull();
    });
  });

  it('should_reset_topbar_widgets_to_all_visible_when_reset_confirmed', async () => {
    // 预置部分隐藏 → 重置（Popconfirm）→ 恢复缺省全显 + 顶栏复显
    setPrefs({ topbarWidgets: { ...allWidgetsVisible(), search: false, lock: false } });
    await renderApp();
    expect(findWidgetInHeader(widgetProbe('search').header), '预置隐藏后 search 应不可见').toBeNull();
    expect(findWidgetInHeader(widgetProbe('lock').header), '预置隐藏后 lock 应不可见').toBeNull();

    const drawer = await openPreferencesDrawer();
    const resetButton = within(drawer).getAllByRole('button', { name: /恢复默认|重置/ })[0];
    expect(resetButton, '恢复默认入口应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(resetButton as HTMLElement);
    });
    await waitFor(() => expect(document.querySelector('.ant-popover:not(.ant-popover-hidden)')).toBeTruthy());
    const popover = document.querySelector('.ant-popover:not(.ant-popover-hidden)') as HTMLElement;
    const confirmButton = within(popover).getAllByRole('button')
      .find((b) => /确|重置|OK|恢复/i.test(b.textContent ?? ''));
    expect(confirmButton, '确认弹窗应提供确认按钮').toBeTruthy();
    await act(async () => {
      fireEvent.click(confirmButton as HTMLElement);
    });

    // 重置后：topbarWidgets 全显缺省 + 顶栏复显 + blob 同步
    await waitFor(() => {
      expect(currentPrefsLoose()['topbarWidgets']).toEqual(allWidgetsVisible());
    });
    await waitFor(() => {
      expect(blobPrefs()?.['topbarWidgets']).toEqual(allWidgetsVisible());
    });
    for (const probe of HEADER_WIDGET_PROBES) {
      expect(findWidgetInHeader(probe.header), `重置后顶栏应恢复 widget ${probe.key}`).toBeTruthy();
    }
    expect(findWidgetInHeader(widgetProbe('search').header), '⑦ Header 搜索框不应随重置复现').toBeNull();
  });

  it('should_keep_tenant_system_config_untouched_when_widget_toggled', async () => {
    // 个人偏好级与租户级正交（契约⑥）：widget 开关不得触碰 systemConfigStore（水印/页脚控制域）
    await renderApp();
    const before = tenantConfigSnapshot();

    const drawer = await openPreferencesDrawer();
    for (const key of ['search', 'lock', 'fullscreen']) {
      const sw = labeledSwitch(drawer, widgetProbe(key).row);
      expect(sw, `抽屉内应存在 ${key} 开关`).toBeTruthy();
      await act(async () => {
        fireEvent.click(sw as HTMLElement);
      });
    }
    await waitFor(() => {
      const widgets = currentPrefsLoose()['topbarWidgets'] as Record<string, unknown> | undefined;
      expect(widgets?.['search']).toBe(false);
    });

    expect(tenantConfigSnapshot(), 'widget 开关不得改变租户级 systemConfigStore 状态').toBe(before);
  });
});

describe('REQ20261122233427 偏好抽屉「布局」分组四控件（AC-7）', () => {
  it('should_render_layout_group_with_four_controls_when_drawer_opened', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();
    const ui = within(drawer);

    // 「布局」分组标签在位（分组文案宽出：布局）
    expect(ui.getAllByText(/布局|layout/i).length, '抽屉应存在「布局」分组标签').toBeGreaterThan(0);

    // 控件一：layoutMode 三选项（sidebar/top/mixed；恰 3 项防同数主题模式组干扰由选项集保证）
    findSegmentedByOptions(drawer, [/侧边|侧栏|sidebar/i, /顶部|顶栏|(^|\s)top(\s|$)/i, /混合|mixed/i], 3);

    // 控件二：tabStyle 四选项（line/card/chrome/plain）
    findSegmentedByOptions(drawer, [
      /线(条|型)?|line/i, /卡片|card/i, /chrome|铬|梯形|经典|标签页/i, /朴素|纯文字|简洁|极简|plain/i,
    ], 4);

    // 控件三：siderTone 两选项（auto/dark；恰 2 项防主题模式组 3 选项撞车）
    findSegmentedByOptions(drawer, [/自动|跟随|auto/i, /深色|暗色|dark/i], 2);

    // 控件四：topbarWidgets 开关组——行标签可命中的 WidgetKey 并集 ≥ 8/9（appearance/settings
    // 行文案撞车容忍 1 键缺位，见文件头校准注记；release 键已退出 UI 呈现）
    const union = drawerWidgetKeyUnion(drawer);
    const missing = WIDGET_PROBES.filter((p) => !union.keys.has(p.key)).map((p) => p.key);
    expect(
      union.keys.size,
      `布局分组应提供 widget 开关组（8/9 键齐备，缺位：${missing.join('/')}；switch 行命中明细：${union.debug}）`,
    ).toBeGreaterThanOrEqual(8);
  });

  it('should_cover_layout_group_labels_in_zh_and_en_without_raw_keys', async () => {
    // zh-CN：四控件锚点 + 无裸 key
    await renderApp();
    const zhDrawer = await openPreferencesDrawer();
    expect(within(zhDrawer).getAllByText(/布局|layout/i).length).toBeGreaterThan(0);
    findSegmentedByOptions(zhDrawer, [/侧边|侧栏|sidebar/i, /顶部|顶栏|top/i, /混合|mixed/i], 3);
    findSegmentedByOptions(zhDrawer, [/线(条|型)?|line/i, /卡片|card/i, /chrome|铬|梯形|经典|标签页/i, /朴素|纯文字|简洁|极简|plain/i], 4);
    findSegmentedByOptions(zhDrawer, [/自动|跟随|auto/i, /深色|暗色|dark/i], 2);
    expect(drawerWidgetKeyUnion(zhDrawer).keys.size).toBeGreaterThanOrEqual(8);
    expect(rawKeyTexts(zhDrawer), 'zh 抽屉不应出现裸 i18n key').toEqual([]);
    cleanup();

    // en-US：同样锚点 + 无裸 key
    await act(async () => {
      await i18n.changeLanguage('en-US');
    });
    await renderApp();
    const enDrawer = await openPreferencesDrawer();
    expect(within(enDrawer).getAllByText(/layout/i).length).toBeGreaterThan(0);
    findSegmentedByOptions(enDrawer, [/sidebar/i, /(^|\s)top(\s|$)/i, /mixed/i], 3);
    findSegmentedByOptions(enDrawer, [/line/i, /card/i, /chrome|classic|tabs?/i, /plain|minimal/i], 4);
    findSegmentedByOptions(enDrawer, [/auto|follow/i, /dark/i], 2);
    const enUnion = drawerWidgetKeyUnion(enDrawer);
    expect(
      enUnion.keys.size,
      `en 布局分组 widget 开关组应 ≥8/9 键（缺位明细：${WIDGET_PROBES.filter((p) => !enUnion.keys.has(p.key)).map((p) => p.key).join('/')}；switch 行命中明细：${enUnion.debug}）`,
    ).toBeGreaterThanOrEqual(8);
    expect(rawKeyTexts(enDrawer), 'en 抽屉不应出现裸 i18n key').toEqual([]);
    cleanup();

    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
  });
});
