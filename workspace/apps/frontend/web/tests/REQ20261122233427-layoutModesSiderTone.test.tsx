/**
 * REQ-20261122-233427（THEME-4）AC-2 三布局模式 + AC-5 半深色 siderTone：web 组件用例。
 *
 * 表面 = 契约确认记录③④⑤ + AC-2/AC-5 + 委派单钉死事实：
 *   - layoutMode('sidebar'|'top'|'mixed')：sidebar=现状逐字；top=顶栏主导航+全宽内容+页签条（无 Sider）；
 *     mixed=顶栏一级（横向主导航）+左侧图标轨（折叠态 72px Sider 语义）；三模式热切换无刷新、路由/页签状态保持；
 *   - siderTone('auto'|'dark')：light 模式+dark 时侧栏/顶栏底色取 DARK_NEUTRALS 子集派生半深色
 *     （经 buildTheme components.Layout.siderBg/headerBg 注入+global.css 变量镜像）；暗色模式下 siderTone 无效；
 *   - 持久化=偏好 blob acme.frontend.ops.v1（core constants）+「刷新保持」。
 *
 * 装配照范本 web/tests/REQ20261121043502-appearanceDrawer.test.tsx（整棵 App + AntApp +
 * HashRouter + ThemedApp 复刻 main.tsx buildTheme 消费点 + 会话/me 播种 + 浏览器桩）；
 * 页签/路由面照范本 web/tests/REQ20261120104022-tabbar-scroll-preview.test.tsx（hash 导航播种）。
 *
 * 交互口径（pitfalls #7/#8/#9/#10 + THEME-2 先例）：
 *   - Segmented 分组以「选项集」锚定（findSegmentedByOptions：分组内全部选项 meta 联合命中才选中），
 *     防多组并存后文本跨组误命中；选项 label 文案契约未钉死 → 正则宽容匹配（pitfalls #14）；
 *   - 视觉面 jsdom 不算 CSS：buildTheme token 断言 + data-attribute/class/inline-style 标记扫描
 *     + global.css 源断言（appearanceDrawer 范本口径）。
 *
 * 新 UI 锚点校准注记（实现交付后自验轮收敛）：top/mixed 横向导航锚 = .ant-menu-horizontal 或
 * [role="menubar"]（antd v5 Menu 横向态稳定类）；mixed 折叠锚 = .ant-layout-sider-collapsed 或
 * inline width ≤ 100px；siderTone 标记锚 = data-attr/class/inline CSS 变量任一承载（契约⑤未钉死承载面）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act } from 'react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import { buildTheme } from '../src/theme';
import {
  DARK_NEUTRALS,
  DEFAULT_PREFERENCES,
  i18n,
  useAuthStore,
  useLockStore,
  useMeStore,
  useNotifyStore,
  type Settings,
} from '@acme/core';

vi.setConfig({ testTimeout: 45000 });
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const BLOB_KEY = 'acme.frontend.ops.v1'; // 契约钉死（packages/core/src/constants.ts）

// ————— 浏览器桩：matchMedia（桌面断点放行 + prefers-color-scheme 捕获）+ ResizeObserver —————

type SchemeListener = (event: { matches: boolean; media: string }) => void;

let schemeListeners: SchemeListener[] = [];
let systemPrefersDark = false;

const stubBrowserGaps = (): void => {
  schemeListeners = [];
  systemPrefersDark = false;
  vi.stubGlobal('matchMedia', (query: string) => {
    const isSchemeQuery = query.includes('prefers-color-scheme');
    return {
      get matches() {
        return query.includes('min-width') || (isSchemeQuery && systemPrefersDark);
      },
      media: query,
      onchange: null,
      addListener: (cb: SchemeListener) => {
        if (isSchemeQuery) schemeListeners.push(cb);
      },
      removeListener: vi.fn(),
      addEventListener: (_type: string, cb: SchemeListener) => {
        if (isSchemeQuery) schemeListeners.push(cb);
      },
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    };
  });
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

// ————— App 壳装配（appearanceDrawer 范本 + tabs 范本 hash 播种） —————

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

/** 统一 fetch 桩（静态图与 resetModules 重载图共用；me 两域 + users 列表 + 伴随空信封）。 */
const installFetchStub = (): void => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.endsWith('/me')) return envelope(mePayload(['system:user:list']));
    if (url.pathname.includes('/system/users')) return envelope({ records: [], total: 0 });
    return envelope(null);
  }));
};

const renderAppAt = async (hash: string): Promise<void> => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
  installFetchStub();
  await useMeStore.getState().load();
  window.location.hash = hash;
  render(<AntApp><HashRouter><ThemedApp /></HashRouter></AntApp>);
};

// ————— settings / blob / store —————

type SettingsLike = { schemaVersion?: number; preferences?: Record<string, unknown> };

const currentPrefsLoose = (): Record<string, unknown> => {
  const prefs = (useStore.getState().state.settings as unknown as SettingsLike).preferences;
  expect(prefs, 'settings 应内嵌 preferences 对象').toBeTruthy();
  return prefs as Record<string, unknown>;
};

const currentThemeConfig = () => buildTheme(useStore.getState().state.settings as unknown as Settings);

const layoutTokens = (): Record<string, unknown> => {
  const components = currentThemeConfig().components as Record<string, unknown> | undefined;
  const layout = components?.['Layout'] as Record<string, unknown> | undefined;
  expect(layout, 'buildTheme 应产出 components.Layout（siderBg/headerBg 注入点，契约⑤）').toBeTruthy();
  return layout as Record<string, unknown>;
};

const expectBlobToContain = (snippet: string): void => {
  const raw = window.localStorage.getItem(BLOB_KEY);
  expect(raw, `偏好应已即时持久化到 ${BLOB_KEY}`).toBeTruthy();
  expect((raw as string).replace(/\s+/g, '')).toContain(snippet);
};

const resetSettingsBaseline = (): void => {
  const store = useStore.getState();
  if (typeof store.resetData === 'function') store.resetData();
  store.dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
};

/**
 * settings store 刷新保持的可测面（010725 preferencesPersist 范本口径）：
 * vi.resetModules + 动态重导入 = 全新 store 实例，其水合路径即应用重启后的恢复路径
 * （settings store 为手写 blob 双写，无 zustand persist API——实测佐证）。
 */
const reloadModuleGraph = async (): Promise<{
  core: typeof import('@acme/core');
  store: typeof import('../src/store/useStore');
  app: typeof import('../src/App');
}> => {
  vi.resetModules();
  const core = await import('@acme/core');
  const store = await import('../src/store/useStore');
  const app = await import('../src/App');
  return { core, store, app };
};

// ————— 顶栏偏好入口与抽屉定位（appearanceDrawer 范本） —————

const headerEl = (): HTMLElement => {
  const header = document.querySelector('header.ant-layout-header');
  expect(header, '顶栏 header.ant-layout-header 应渲染').toBeTruthy();
  return header as HTMLElement;
};

const preferenceEntry = (): HTMLElement => {
  const buttons = within(headerEl()).queryAllByRole('button');
  const entry = buttons.find((button) => {
    const name = `${button.getAttribute('aria-label') ?? ''} ${button.getAttribute('title') ?? ''} ${button.textContent ?? ''}`;
    return /偏好|主题|外观|preference/i.test(name);
  });
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
 * 以「选项集」锚定 Segmented 分组：分组内全部 optionMatchers 各自在选项 meta 中命中才选中；
 * expectedCount 可选——按选项数判别（siderTone 两选项 vs 主题模式三选项 auto/dark 文案撞车、
 * layoutMode 三选项 vs 主题模式三选项同数场景），防跨组误命中（实测：F1 首轮 siderTone 锚曾误中主题模式组）。
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

/** Segmented 项点击（appearanceDrawer 范本口径：jsdom 下直接点击隐藏 input 提交）。 */
const pickInGroup = async (group: HTMLElement, label: RegExp): Promise<void> => {
  const items = Array.from(group.querySelectorAll<HTMLElement>('.ant-segmented-item'));
  for (const item of items) {
    const meta = [
      item.textContent ?? '',
      item.getAttribute('title') ?? '',
      item.querySelector('.ant-segmented-item-label')?.getAttribute('title') ?? '',
      ...Array.from(item.querySelectorAll('[aria-label]')).map((el) => el.getAttribute('aria-label') ?? ''),
    ].join(' ');
    if (!label.test(meta)) continue;
    const input = item.querySelector<HTMLInputElement>('input[type="radio"]');
    await act(async () => {
      if (input) fireEvent.click(input);
      else fireEvent.click(item);
    });
    return;
  }
  expect.fail(`分组 Segmented 中未找到选项 ${label}（实际项：${items.map((i) => i.textContent ?? '').join('/')}）`);
};

// ————— 布局结构锚（契约③；top/mixed 横向导航锚见文件头校准注记） —————

const siderEl = (): HTMLElement | null => document.querySelector('aside.ant-layout-sider');

const horizontalNav = (): Element | null =>
  document.querySelector('.ant-menu-horizontal, [role="menubar"]');

const siderIsCollapsed = (): boolean => {
  const sider = siderEl();
  if (!sider) return false;
  if (sider.classList.contains('ant-layout-sider-collapsed')) return true;
  const width = /width\s*:\s*(\d+)px/.exec(sider.getAttribute('style') ?? '')?.[1];
  return width ? Number(width) <= 100 : false;
};

const expectSiderHidden = (timeout = 10000) => waitFor(() => {
  expect(siderEl(), 'top 布局下不应渲染 Sider（aside.ant-layout-sider）').toBeNull();
}, { timeout });

const expectHorizontalNav = (message: string, timeout = 10000) => waitFor(() => {
  expect(horizontalNav(), message).toBeTruthy();
}, { timeout });

const expectSiderShown = (timeout = 10000) => waitFor(() => {
  expect(siderEl(), 'sidebar/mixed 布局下应渲染 Sider').toBeTruthy();
}, { timeout });

// ————— 页签播种（tabs 范本口径） —————

const tabEl = (path: string): HTMLElement | null =>
  document.querySelector(`.tabbar-tab[data-tab-path="${path}"]`) as HTMLElement | null;

const wait = (assertion: () => void, timeout = 10000) => waitFor(assertion, { timeout });

// ————— AC-5 视觉面：半深色派生值与标记扫描 —————

const darkValueSet = new Set(Object.values(DARK_NEUTRALS).map((v) => String(v).toLowerCase()));

const isDarkish = (value: unknown): boolean => {
  const raw = String(value ?? '').toLowerCase();
  if (darkValueSet.has(raw)) return true;
  return Object.values(DARK_NEUTRALS).some((d) => raw.includes(String(d).toLowerCase()));
};

/** siderTone 视觉标记扫描（契约⑤未钉死承载面：data-attr/class/inline CSS 变量任一承载即合规）。 */
const toneMarks = (): string[] => {
  const hosts: Element[] = [document.documentElement, document.body, ...Array.from(document.querySelectorAll('*'))];
  const found: string[] = [];
  for (const host of hosts) {
    for (const attr of Array.from(host.attributes ?? [])) {
      if (attr.name.startsWith('data-') && /tone|半深|sider[-_ ]?(dark|深)/i.test(`${attr.name}=${attr.value}`)) {
        found.push(`${host.tagName}:${attr.name}=${attr.value}`);
      }
    }
    const cls = (host as HTMLElement).className;
    if (typeof cls === 'string' && /sider[-_ ]?(dark|tone|深)|dark[-_ ]?sider|half[-_ ]?dark/i.test(cls)) {
      found.push(`${host.tagName}:class`);
    }
    const style = (host as HTMLElement).style?.cssText ?? '';
    if (/--[a-z-]*(sider|tone|半深)[a-z-]*\s*:/.test(style)) found.push(`${host.tagName}:css-var`);
  }
  return found;
};

/** global.css 源（视觉单源镜像；appearanceDrawer 范本按 cwd 定位）。 */
const globalCssPath = ['./src/styles/global.css', '../src/styles/global.css']
  .map((relative) => resolve(process.cwd(), relative))
  .find((candidate) => existsSync(candidate));
const globalCss = (): string => readFileSync(globalCssPath as string, 'utf8');

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

describe('REQ20261122233427 三布局热切换（AC-2）', () => {
  it('should_hot_switch_three_layout_modes_with_route_and_tabs_kept_when_drawer_control_used', async () => {
    // 播种双页签（/ 永久 + /users）后经抽屉切换三布局：结构面 + 持久化 + 路由/页签保持
    await renderAppAt('#/');
    await wait(() => expect(tabEl('/')).toBeTruthy());
    window.location.hash = '#/users';
    await wait(() => expect(tabEl('/users')).toBeTruthy());
    const drawer = await openPreferencesDrawer();

    // 基线：缺省 sidebar=现状逐字（Sider 展开在位、无横向主导航）
    await wait(() => expect(currentPrefsLoose()['layoutMode'] ?? 'sidebar').toBe('sidebar'));
    expectSiderShown();
    expect(horizontalNav(), 'sidebar 布局不应出现横向主导航').toBeNull();

    const layoutGroup = findSegmentedByOptions(drawer, [
      /侧边|侧栏|sidebar/i, /顶部|顶栏|(^|\s)top(\s|$)/i, /混合|mixed/i,
    ], 3);

    // ① top：Sider 移除 + 横向主导航出现 + 持久化 + 路由/页签保持（热切换无刷新的观测面）
    await pickInGroup(layoutGroup, /顶部|顶栏|(^|\s)top(\s|$)/i);
    await wait(() => expect(currentPrefsLoose()['layoutMode']).toBe('top'));
    await expectSiderHidden();
    await expectHorizontalNav('top 布局应以横向主导航承载一级菜单');
    expectBlobToContain('"layoutMode":"top"');
    expect(window.location.hash).toBe('#/users');
    await wait(() => {
      expect(tabEl('/users'), '切换布局不得丢页签').toBeTruthy();
      expect(tabEl('/users')?.getAttribute('aria-selected')).toBe('true');
      expect(tabEl('/'), '永久仪表盘页签不得丢').toBeTruthy();
    });
    expect(drawer.classList.contains('ant-drawer-open'), '热切换后抽屉保持打开（无整树刷新）').toBe(true);

    // ② mixed：Sider 回归（折叠态 72px 语义）+ 横向主导航 + 持久化
    const mixedGroup = findSegmentedByOptions(drawer, [
      /侧边|侧栏|sidebar/i, /顶部|顶栏|(^|\s)top(\s|$)/i, /混合|mixed/i,
    ], 3);
    await pickInGroup(mixedGroup, /混合|mixed/i);
    await wait(() => expect(currentPrefsLoose()['layoutMode']).toBe('mixed'));
    await expectSiderShown();
    await expectHorizontalNav('mixed 布局应保留顶栏横向主导航');
    await wait(() => expect(siderIsCollapsed(), 'mixed 布局 Sider 应为折叠态 72px 图标轨语义').toBe(true));
    expectBlobToContain('"layoutMode":"mixed"');
    expect(window.location.hash).toBe('#/users');

    // ③ 切回 sidebar：横向主导航消失、Sider 展开回归
    const backGroup = findSegmentedByOptions(drawer, [
      /侧边|侧栏|sidebar/i, /顶部|顶栏|(^|\s)top(\s|$)/i, /混合|mixed/i,
    ], 3);
    await pickInGroup(backGroup, /侧边|侧栏|sidebar/i);
    await wait(() => expect(currentPrefsLoose()['layoutMode']).toBe('sidebar'));
    await expectSiderShown();
    await wait(() => expect(horizontalNav(), '切回 sidebar 后横向主导航应消失').toBeNull());
    await wait(() => expect(siderIsCollapsed(), 'sidebar 布局 Sider 应为展开态').toBe(false));
    expectBlobToContain('"layoutMode":"sidebar"');
  });

  it('should_restore_layout_mode_from_blob_after_remount_when_persisted', async () => {
    // 刷新保持：切到 top → 冷启动重载（resetModules + 动态重导入）→ 仍为 top（Sider 不回归）
    await renderAppAt('#/');
    const drawer = await openPreferencesDrawer();
    const layoutGroup = findSegmentedByOptions(drawer, [
      /侧边|侧栏|sidebar/i, /顶部|顶栏|(^|\s)top(\s|$)/i, /混合|mixed/i,
    ], 3);
    await pickInGroup(layoutGroup, /顶部|顶栏|(^|\s)top(\s|$)/i);
    await wait(() => expect(currentPrefsLoose()['layoutMode']).toBe('top'));
    await expectSiderHidden();
    expectBlobToContain('"layoutMode":"top"');

    cleanup();
    window.location.hash = '';
    // 重载图：全新 store/core/App 实例；重新播种会话与 me（等价重启后的登录态），hash 走仪表盘
    const { core, store, app } = await reloadModuleGraph();
    core.useAuthStore.getState().setSession({
      accessToken: 'token-1',
      expiresAtMs: Date.now() + 1_800_000,
      userId: '1001',
      username: 'admin',
      nickname: null,
      tenantCode: 'default',
    }, false);
    installFetchStub();
    await core.useMeStore.getState().load();
    window.location.hash = '#/';
    render(<AntApp><HashRouter><app.App /></HashRouter></AntApp>);
    const freshSettings = (store.useStore.getState().state.settings) as unknown as SettingsLike;
    const freshPrefs = freshSettings.preferences as Record<string, unknown>;
    await wait(() => expect(freshPrefs['layoutMode'], '重载后 layoutMode 应保持 top（刷新保持）').toBe('top'));
    await expectSiderHidden();
  });
});

describe('REQ20261122233427 半深色 siderTone（AC-5）', () => {
  it('should_apply_half_dark_sider_and_header_when_light_mode_with_sider_tone_dark', async () => {
    // light + auto 基线快照 → light + dark：siderBg/headerBg 派生自 DARK_NEUTRALS 子集（契约⑤）
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          siderTone: 'auto',
          mode: 'light',
        } as unknown as Settings['preferences'],
      },
    });
    await renderAppAt('#/');
    const autoLayout = layoutTokens();
    const autoSiderBg = String(autoLayout['siderBg'] ?? '');
    expect(autoSiderBg, 'light+auto 基线 siderBg 应有值').toBeTruthy();

    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          siderTone: 'dark',
          mode: 'light',
        } as unknown as Settings['preferences'],
      },
    });
    await wait(() => expect(currentPrefsLoose()['siderTone']).toBe('dark'));

    const darkLayout = layoutTokens();
    const darkSiderBg = String(darkLayout['siderBg'] ?? '');
    const darkHeaderBg = String(darkLayout['headerBg'] ?? '');
    expect(darkSiderBg, 'siderTone=dark 应产出非空 siderBg').toBeTruthy();
    // 半深色派生：与 light+auto 基线不同（auto 基线为浅色/透明），且自身非 transparent
    expect(darkSiderBg.toLowerCase(), `siderTone=dark 的 siderBg 不得沿用 auto 基线值（auto=${autoSiderBg}）`)
      .not.toBe(autoSiderBg.toLowerCase());
    expect(darkSiderBg.toLowerCase()).not.toBe('transparent');
    expect(isDarkish(darkSiderBg), `siderBg 应取暗色中性盘子值（实际 ${darkSiderBg}；DARK_NEUTRALS 样本 ${[...darkValueSet].slice(0, 4).join('/')}）`).toBe(true);
    expect(darkHeaderBg, 'siderTone=dark 应同步产出 headerBg').toBeTruthy();
    expect(isDarkish(darkHeaderBg), `headerBg 应取暗色中性盘子值（实际 ${darkHeaderBg}）`).toBe(true);

    // DOM 承载面：根容器/侧栏 data-attribute、类名或 inline CSS 变量任一承载半深色标记（契约⑤未钉死承载面）
    await wait(() => expect(toneMarks().length, `应存在 siderTone 视觉标记（data-*/class/css 变量），实际扫描结果 ${JSON.stringify(toneMarks())}`).toBeGreaterThan(0));

    // CSS 源：global.css 应镜像半深色规则（sider×tone/dark 语义规则至少一条）
    const css = globalCss();
    const siderToneRules = css
      .split('\n')
      .filter((line) => /sider/i.test(line) && /tone|dark|深|半/i.test(line));
    expect(siderToneRules.length, `global.css 应存在 siderTone 半深色规则/变量镜像（sider×tone/dark 行）`).toBeGreaterThan(0);
    expectBlobToContain('"siderTone":"dark"');
  });

  it('should_keep_layout_tokens_unchanged_when_dark_mode_with_sider_tone_dark', async () => {
    // 暗色模式下 siderTone 无效（本就暗）：auto vs dark 的 Layout 派生值不变
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          siderTone: 'auto',
          mode: 'dark',
        } as unknown as Settings['preferences'],
      },
    });
    await renderAppAt('#/');
    const autoLayout = layoutTokens();
    const autoSiderBg = String(autoLayout['siderBg'] ?? '');
    const autoHeaderBg = String(autoLayout['headerBg'] ?? '');

    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          siderTone: 'dark',
          mode: 'dark',
        } as unknown as Settings['preferences'],
      },
    });
    await wait(() => expect(currentPrefsLoose()['siderTone']).toBe('dark'));

    const darkLayout = layoutTokens();
    expect(String(darkLayout['siderBg'] ?? ''), '暗色模式下 siderTone=dark 不得改变 siderBg').toBe(autoSiderBg);
    expect(String(darkLayout['headerBg'] ?? ''), '暗色模式下 siderTone=dark 不得改变 headerBg').toBe(autoHeaderBg);
  });

  it('should_toggle_sider_tone_from_drawer_and_persist_when_light_mode', async () => {
    // 抽屉 UI 面联动：light 模式下 siderTone 分组切 dark → prefs + buildTheme + blob 同步
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: { ...DEFAULT_PREFERENCES, mode: 'light' } as unknown as Settings['preferences'],
      },
    });
    await renderAppAt('#/');
    const drawer = await openPreferencesDrawer();
    // siderTone 分组=恰两选项（auto/dark），防与主题模式组（亮色/暗色/跟随系统）文案撞车误命中
    const toneGroup = findSegmentedByOptions(drawer, [/自动|跟随|auto/i, /深色|暗色|dark/i], 2);
    await pickInGroup(toneGroup, /深色|暗色|dark/i);

    await wait(() => expect(currentPrefsLoose()['siderTone']).toBe('dark'));
    await wait(() => expect(isDarkish(String(layoutTokens()['siderBg'] ?? '')), '抽屉切 dark 后 siderBg 应立即派生半深色').toBe(true));
    expectBlobToContain('"siderTone":"dark"');
  });
});
