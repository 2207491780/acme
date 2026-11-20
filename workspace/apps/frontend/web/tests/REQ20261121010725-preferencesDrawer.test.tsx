/**
 * REQ-20261121-010725 AC-5~AC-11：web 偏好抽屉契约用例（vben 形态，顶栏入口）。
 *
 * 装配照范本 web/tests/REQ20261114120101-nav.test.tsx（整棵 App + AntApp + HashRouter +
 * 会话/me 播种 + 浏览器桩）；主题接线按契约钉死消费点「main.tsx：<ConfigProvider
 * theme={buildTheme(settings)}>」以 useStore 订阅 settings 复刻（ThemedApp）。
 *
 * 交互口径（pitfalls #7/#8/#9/#10 + 071607 裁决⑪）：
 *   - 选项点击优先文本命中（Segmented/Radio/按钮/已展开选项），Select 走 .ant-select-selector
 *     展开 + 浮层容器内按文本选中；
 *   - Drawer 开合按数据驱动断言（.ant-drawer-open 与 open prop 同步），禁等动效完成信号；
 *   - 文案断言宽容正则（语义匹配），不绑具体 i18n key 名（键名归 frontend 定）。
 *
 * 主题面断言走契约钉死管线 buildTheme(settings) token（jsdom cssinjs 计算样式不可靠，不做样式断言）。
 * 「重载保留」的 store 级验证见 REQ20261121010725-preferencesPersist.test.tsx（resetModules 重导入口径）。
 */
import { act } from 'react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp, ConfigProvider, theme as antdTheme } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import { buildTheme } from '../src/theme';
import {
  DEFAULT_PREFERENCES,
  resolveAccentColor,
  i18n,
  useAuthStore,
  useMeStore,
  type Settings,
  type ThemePreferences,
} from '@acme/core';

vi.setConfig({ testTimeout: 20000 });
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const BLOB_KEY = 'acme.frontend.ops.v1'; // 契约钉死（packages/core/src/constants.ts）

// ————— 浏览器桩：matchMedia（捕获 prefers-color-scheme 查询与监听）+ ResizeObserver —————

type SchemeListener = (event: { matches: boolean; media: string }) => void;

let mediaQueries: string[] = [];
let schemeListeners: SchemeListener[] = [];
let systemPrefersDark = false;

const stubBrowserGaps = (): void => {
  mediaQueries = [];
  schemeListeners = [];
  systemPrefersDark = false;
  vi.stubGlobal('matchMedia', (query: string) => {
    mediaQueries.push(query);
    const isSchemeQuery = query.includes('prefers-color-scheme');
    return {
      get matches() {
        // 桌面布局断点放行（nav 范本 A-24）；系统深色动态可变
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

/** 派发 prefers-color-scheme change（即时联动 web 侧监听 hook）。 */
const fireSchemeChange = (dark: boolean): void => {
  systemPrefersDark = dark;
  for (const listener of schemeListeners) {
    listener({ matches: dark, media: '(prefers-color-scheme: dark)' });
  }
};

// ————— App 壳装配（nav 范本 + ThemedApp 主题接线） —————

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
  menus: [], // 静态回退导航即可，断言不依赖动态菜单
});

/** 复刻 main.tsx 消费点：ConfigProvider theme=buildTheme(settings)（契约背景钉死）。 */
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
    if (url.pathname.includes('/system/me')) return envelope(mePayload(['system:user:list']));
    return envelope(null); // Dashboard 等伴随请求
  }));
  await useMeStore.getState().load();
  window.location.hash = '#/';
  render(<AntApp><HashRouter><ThemedApp /></HashRouter></AntApp>);
  // 外壳就绪锚（顶栏挂载，nav 范本口径）；AC-11 需在 en-US 下渲染 → 语言中立锚。
  // 全等锚定：/设置/ 会同时命中偏好入口 aria-label「偏好设置」
  await screen.findByRole('button', { name: /^(设置|Settings)$/ });
};

// ————— settings / blob 读取 —————

type SettingsLike = { schemaVersion?: number; preferences?: ThemePreferences };

const currentSettings = (): SettingsLike => useStore.getState().state.settings as unknown as SettingsLike;

const currentPrefs = (): ThemePreferences => {
  const prefs = currentSettings().preferences;
  expect(prefs, 'settings 应内嵌 preferences 对象（TL 钉死面）').toBeTruthy();
  return prefs as ThemePreferences;
};

const currentThemeConfig = () => buildTheme(useStore.getState().state.settings as unknown as Settings);

const expectBlobToContain = (snippet: string): void => {
  const raw = window.localStorage.getItem(BLOB_KEY);
  expect(raw, `偏好应已即时持久化到 ${BLOB_KEY}`).toBeTruthy();
  expect((raw as string).replace(/\s+/g, '')).toContain(snippet);
};

/** 测试基线：重置到种子态后统一写默认偏好（隔离上一用例遗留）。 */
const resetSettingsBaseline = (): void => {
  const store = useStore.getState();
  if (typeof store.resetData === 'function') store.resetData();
  store.dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
};

// ————— 顶栏偏好入口与抽屉定位 —————

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
  expect(entry, '顶栏应存在偏好入口按钮（带已选强调色圆点视觉提示）').toBeTruthy();
  return entry as HTMLElement;
};

const openPreferencesDrawer = async (): Promise<HTMLElement> => {
  await act(async () => {
    fireEvent.click(preferenceEntry());
  });
  // 数据驱动断言（071607 裁决⑪）：.ant-drawer-open 与 open prop 同步，禁等动效
  await waitFor(() => expect(document.querySelector('.ant-drawer-open')).toBeTruthy());
  return document.querySelector('.ant-drawer-open') as HTMLElement;
};

// ————— 交互工具（文本优先，Select 走 pitfalls #7/#8 口径） —————

/**
 * Segmented 口径（渲染树实测）：项 = label.ant-segmented-item + 隐藏 input[type=radio]，
 * 项身份 = 文本/title（密度）或内层图标 aria-label（主题模式为 icon-only：sun/moon/desktop）。
 * jsdom 不把 label 点击转发给 radio（pitfalls #9 渲染树事实）→ 直接点击 input 提交。
 */
const segmentedOption = async (scope: HTMLElement, label: RegExp): Promise<boolean> => {
  for (const group of Array.from(scope.querySelectorAll<HTMLElement>('.ant-segmented'))) {
    for (const item of Array.from(group.querySelectorAll<HTMLElement>('.ant-segmented-item'))) {
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
      return true;
    }
  }
  return false;
};

const pickOption = async (scope: HTMLElement, label: RegExp): Promise<void> => {
  if (await segmentedOption(scope, label)) return;
  const direct = within(scope).queryAllByText(label);
  if (direct.length > 0) {
    const target = direct[0].closest(
      'label,button,[role="radio"],[role="menuitem"],[role="option"],.ant-segmented-item,.ant-select-item',
    ) ?? direct[0];
    const input = target.querySelector<HTMLInputElement>('input[type="radio"]');
    await act(async () => {
      fireEvent.click(input ?? target);
    });
    return;
  }
  // Select 口径（pitfalls #7/#8）：mousedown 展开 .ant-select-selector，浮层容器内限定按文本选中
  const selector = scope.querySelector('.ant-select-selector');
  expect(selector, `抽屉内既无文本项 ${label} 也无 Select 选择器`).toBeTruthy();
  await act(async () => {
    fireEvent.mouseDown(selector as Element);
  });
  await waitFor(() => expect(document.querySelector('.ant-select-dropdown')).toBeTruthy());
  const dropdown = document.querySelector('.ant-select-dropdown') as HTMLElement;
  const picked = within(dropdown).queryAllByText(label);
  expect(picked.length, `Select 浮层内未找到选项 ${label}`).toBeGreaterThan(0);
  await act(async () => {
    fireEvent.click(picked[0].closest('[role="option"],.ant-select-item') ?? picked[0]);
  });
};

const hexToRgb = (hex: string): string => {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  expect(match, `测试工具仅支持 6 位 hex：${hex}`).toBeTruthy();
  const [r, g, b] = [match![1], match![2], match![3]].map((part) => parseInt(part, 16));
  return `rgb(${r}, ${g}, ${b})`;
};

/** 在作用域内按色值（hex/rgb/名称元数据）定位强调色预设圆点。 */
const findSwatch = (scope: HTMLElement, hex: string): HTMLElement | null => {
  const lower = hex.toLowerCase();
  const rgb = hexToRgb(hex).toLowerCase();
  const candidates = Array.from(scope.querySelectorAll<HTMLElement>('[style], [title], [aria-label]'));
  return (
    candidates.find((el) => {
      const style = (el.getAttribute('style') ?? '').toLowerCase();
      const meta = `${el.getAttribute('title') ?? ''} ${el.getAttribute('aria-label') ?? ''}`.toLowerCase();
      return style.includes(lower) || style.includes(rgb) || meta.includes(lower);
    }) ?? null
  );
};

const pickSwatch = async (scope: HTMLElement, hex: string): Promise<void> => {
  const swatch = findSwatch(scope, hex);
  expect(swatch, `强调色网格应含预设圆点 ${hex}`).toBeTruthy();
  await act(async () => {
    fireEvent.click(swatch as HTMLElement);
  });
};

/** 强调色网格规模（行内样式含背景色元素的规模，网格 12 预设 + 自定义入口的下界锚）。 */
const countSwatchLike = (scope: HTMLElement): number => {
  const styleHits = Array.from(scope.querySelectorAll<HTMLElement>('[style]')).filter((el) => {
    const style = (el.getAttribute('style') ?? '').toLowerCase();
    return /background(-color)?\s*:/.test(style) && /(#[0-9a-f]{3,8}|rgb|hsl)/.test(style);
  }).length;
  const titled = Array.from(scope.querySelectorAll<HTMLElement>('[title]')).filter((el) =>
    /#[0-9a-f]{6}/i.test(el.getAttribute('title') ?? '')).length;
  return Math.max(styleHits, titled);
};

/**
 * 自定义取色：ColorPicker 触发器（.ant-color-picker-trigger div）→ 弹层面板
 * （.ant-popover.ant-color-picker，渲染树实测）→ hex 输入（.ant-color-picker-hex-input input）
 * change 即提交（渲染树实测）；Enter/blur 兜底。
 */
const applyCustomColor = async (scope: HTMLElement, hex: string): Promise<void> => {
  let trigger = scope.querySelector<HTMLElement>('.ant-color-picker-trigger');
  if (!trigger) {
    const customEntry = within(scope).queryByText(/自定义|自定|取色|custom/i);
    if (customEntry) {
      await act(async () => {
        fireEvent.click(customEntry.closest('button') ?? customEntry);
      });
    }
    trigger = (document.querySelector('.ant-color-picker-trigger')
      ?? scope.querySelector('.ant-color-picker-trigger')) as HTMLElement | null;
  }
  expect(trigger, '抽屉内应存在自定义取色入口（ColorPicker）').toBeTruthy();
  await act(async () => {
    fireEvent.click(trigger as HTMLElement);
  });
  await waitFor(() => {
    // antd 5.22 弹层根 = .ant-popover.ant-color-picker；.ant-color-picker* 兜底兼容版本差异
    expect(document.querySelector('.ant-popover.ant-color-picker, .ant-color-picker')).toBeTruthy();
  });
  const panel = (document.querySelector('.ant-popover.ant-color-picker, .ant-color-picker')
    ?? document) as HTMLElement;
  const input = panel.querySelector<HTMLInputElement>('.ant-color-picker-hex-input input');
  expect(input, 'ColorPicker 面板应提供 hex 输入框').toBeTruthy();
  await act(async () => {
    fireEvent.change(input as HTMLInputElement, { target: { value: hex } });
    fireEvent.keyDown(input as HTMLElement, { key: 'Enter', code: 'Enter' });
    (input as HTMLInputElement).blur();
  });
};

/** 按分组文案定位开关（动效 Switch；向上 4 层内命中标签者）。 */
const labeledSwitch = (scope: HTMLElement, label: RegExp): HTMLElement | null => {
  const hit = within(scope).queryAllByRole('switch').find((sw) => {
    let node: HTMLElement | null = sw as HTMLElement;
    for (let depth = 0; depth < 4 && node; depth += 1) {
      if (label.test(node.textContent ?? '')) return true;
      node = node.parentElement;
    }
    return false;
  });
  return hit ?? null;
};

// ————— i18n 裸 key 扫描（AC-11：无裸 key） —————

const RAW_KEY_RE = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$/;
const rawKeyTexts = (scope: HTMLElement): string[] =>
  Array.from(scope.querySelectorAll('*'))
    .map((el) => (el.childElementCount === 0 ? (el.textContent ?? '').trim() : ''))
    .filter((text) => RAW_KEY_RE.test(text));

// ————— 用例 —————

beforeEach(async () => {
  stubBrowserGaps();
  await act(async () => {
    await i18n.changeLanguage('zh-CN'); // jsdom navigator.language=en-US，钉基线防漂移（nav 范本口径）
  });
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  resetSettingsBaseline();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  window.location.hash = '';
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  resetSettingsBaseline();
  await act(async () => {
    await i18n.changeLanguage('zh-CN');
  });
});

describe('REQ20261121010725 偏好抽屉（AC-5/AC-6/AC-7/AC-8/AC-9）', () => {
  it('should_render_preference_drawer_groups_when_header_entry_clicked', async () => {
    await renderApp();
    expect(preferenceEntry()).toBeTruthy(); // 顶栏偏好入口存在
    const drawer = await openPreferencesDrawer();
    const ui = within(drawer);

    // 打开不阻塞页面：顶栏仍在（交互规格）
    expect(within(headerEl()).queryAllByRole('button').length).toBeGreaterThan(0);

    // 分组：主题模式（三选：浅色/深色/跟随系统）
    expect(ui.getAllByText(/主题模式/).length).toBeGreaterThan(0);
    expect(ui.getAllByText(/浅色|亮色/).length).toBeGreaterThan(0);
    expect(ui.getAllByText(/深色|暗色/).length).toBeGreaterThan(0);
    expect(ui.getAllByText(/跟随系统/).length).toBeGreaterThan(0);

    // 分组：强调色（预设网格 12 + 自定义取色入口）
    expect(ui.getAllByText(/强调色|主题色/).length).toBeGreaterThan(0);
    expect(countSwatchLike(drawer)).toBeGreaterThanOrEqual(12);
    expect(
      drawer.querySelector('.ant-color-picker-trigger') !== null
      || ui.queryAllByText(/自定义|取色/).length > 0,
    ).toBe(true);

    // 分组：密度（两档）与动效（开关）
    expect(ui.getAllByText(/密度/).length).toBeGreaterThan(0);
    expect(ui.getAllByText(/舒适|宽松|默认/).length).toBeGreaterThan(0);
    expect(ui.getAllByText(/紧凑/).length).toBeGreaterThan(0);
    expect(ui.queryAllByRole('switch').length).toBeGreaterThan(0);

    // 恢复默认入口
    expect(ui.queryAllByRole('button', { name: /恢复默认|重置/ }).length).toBeGreaterThan(0);
  });

  it('should_apply_system_mode_instantly_and_persist_when_picked', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();

    // 主题模式为 icon-only Segmented（渲染树实测：sun/moon/desktop）→ 项身份含图标 aria-label
    await pickOption(drawer, /跟随系统|系统|desktop/);

    // 即时生效：settings.preferences.mode 即时切换为 system
    await waitFor(() => expect(currentPrefs().mode).toBe('system'));
    // 即时持久化（重载恢复由 preferencesPersist 探针文件口径覆盖）
    expectBlobToContain('"mode":"system"');
    // system 模式接通 prefers-color-scheme 监听（TL 钉死：web 侧 matchMedia 监听 hook）
    expect(mediaQueries.some((q) => q.includes('prefers-color-scheme'))).toBe(true);
  });

  it('should_resolve_system_mode_to_dark_tokens_when_system_prefers_dark', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();
    await pickOption(drawer, /跟随系统|系统|desktop/);
    await waitFor(() => expect(currentPrefs().mode).toBe('system'));

    // 系统切深色 → 主题即时解析为 dark
    await act(async () => {
      fireSchemeChange(true);
    });
    expect(currentThemeConfig().algorithm).toBe(antdTheme.darkAlgorithm);
    // 系统切浅色 → 解析回 light
    await act(async () => {
      fireSchemeChange(false);
    });
    expect(currentThemeConfig().algorithm).toBe(antdTheme.defaultAlgorithm);

    // 手动选浅色即脱离 system：系统再变深色也不改变用户选择（AC-4 web 面）
    await pickOption(drawer, /浅色|亮色|sun/);
    await waitFor(() => expect(currentPrefs().mode).toBe('light'));
    await act(async () => {
      fireSchemeChange(true);
    });
    expect(currentPrefs().mode).toBe('light');
    expect(currentThemeConfig().algorithm).toBe(antdTheme.defaultAlgorithm);
  });

  it('should_apply_custom_accent_instantly_when_color_picked', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();

    await applyCustomColor(drawer, '#b08968');

    // 即时生效：customAccent 写入 → resolveAccentColor 取自定义值 → colorPrimary 联动
    await waitFor(() => expect(currentPrefs().customAccent).toBe('#b08968'));
    expect(resolveAccentColor(currentPrefs())).toBe('#b08968');
    expect(currentThemeConfig().token?.colorPrimary).toBe('#b08968');
    // ON_ACCENT 纯黑规则保持对比度（确认记录⑤）
    expect(currentThemeConfig().token?.colorTextLightSolid).toBe('#000000');
    // 即时持久化
    expectBlobToContain('"customAccent":"#b08968"');
  });

  it('should_apply_and_persist_each_preference_when_changed', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();

    // ① 预设色 emerald（TL 钉死新键值 #58aa82）
    await pickSwatch(drawer, '#58aa82');
    await waitFor(() => expect(currentPrefs().accent).toBe('emerald'));
    expect(resolveAccentColor(currentPrefs())).toBe('#58aa82');
    expect(currentThemeConfig().token?.colorPrimary).toBe('#58aa82');
    expectBlobToContain('"accent":"emerald"');

    // ② 密度 compact（即时应用 + 持久化；token 面与既有 web theme 基线一致）
    await pickOption(drawer, /紧凑/);
    await waitFor(() => expect(currentPrefs().density).toBe('compact'));
    expect(currentThemeConfig().components?.Table?.cellPaddingBlock).toBe(8);
    expectBlobToContain('"density":"compact"');

    // ③ 动效关闭
    const motionSwitch = labeledSwitch(drawer, /动效/);
    expect(motionSwitch, '动效开关应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(motionSwitch as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().motionEnabled).toBe(false));
    expectBlobToContain('"motionEnabled":false');
  });

  it('should_reset_all_preferences_when_reset_confirmed', async () => {
    // 预置非默认偏好
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: { mode: 'dark', accent: 'emerald', customAccent: '#b08968', density: 'compact', motionEnabled: false },
      },
    });
    await renderApp();
    const drawer = await openPreferencesDrawer();
    expect(currentPrefs()).toEqual({
      mode: 'dark', accent: 'emerald', customAccent: '#b08968', density: 'compact', motionEnabled: false,
    });

    const resetButton = within(drawer).getAllByRole('button', { name: /恢复默认|重置/ })[0];
    expect(resetButton, '恢复默认入口应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(resetButton as HTMLElement);
    });

    // 二次确认（Popconfirm）
    await waitFor(() => expect(document.querySelector('.ant-popover:not(.ant-popover-hidden)')).toBeTruthy());
    const popover = document.querySelector('.ant-popover:not(.ant-popover-hidden)') as HTMLElement;
    const confirmButton = within(popover).getAllByRole('button')
      .find((b) => /确|重置|OK|恢复/i.test(b.textContent ?? ''));
    expect(confirmButton, '确认弹窗应提供确认按钮').toBeTruthy();
    await act(async () => {
      fireEvent.click(confirmButton as HTMLElement);
    });

    // 全部偏好回出厂默认并即时生效
    await waitFor(() => expect(currentPrefs()).toEqual({ ...DEFAULT_PREFERENCES }));
    expectBlobToContain('"mode":"light"');
    expectBlobToContain('"accent":"cyan"');
    expect(currentThemeConfig().token?.colorPrimary).toBe('#5aa7a0');
  }, 30000); // 本用例含预置非默认偏好 + 整棵 App 渲染 + Popconfirm 交互，串行实测 16-18s，放宽单例时限（nav 范本口径）
});

describe('REQ20261121010725 设置页外观卡迁移（AC-10）', () => {
  it('should_remove_appearance_card_from_settings_when_migrated', async () => {
    await renderApp();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '设置' }));
    });
    await waitFor(() => expect(window.location.hash).toBe('#/settings'), { timeout: 5000 });

    const content = (document.querySelector('main.ant-layout-content') ?? document.body) as HTMLElement;
    const page = within(content);
    // 其余设置卡不受影响（锚：安全卡 + 数据管理/检查更新）
    await waitFor(() => expect(page.getAllByText(/安\s*全/).length).toBeGreaterThan(0));
    expect(page.queryAllByText(/数据管理|检查更新/).length).toBeGreaterThan(0);
    // 外观卡移除：外观分组/强调色/密度文案不再出现
    expect(page.queryAllByText(/外观/)).toHaveLength(0);
    expect(page.queryAllByText(/强调色|主题色/)).toHaveLength(0);
    expect(page.queryAllByText(/密度/)).toHaveLength(0);
  });
});

describe('REQ20261121010725 抽屉文案双语覆盖（AC-11）', () => {
  it('should_cover_all_drawer_strings_in_zh_and_en', async () => {
    // zh-CN
    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
    await renderApp();
    const zhDrawer = await openPreferencesDrawer();
    const zh = within(zhDrawer);
    expect(zh.getAllByText(/主题模式|主题/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/浅色|亮色/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/深色|暗色/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/跟随系统|系统/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/强调色|主题色|强调/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/密度/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/动效|动画|过渡/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/恢复默认|重置/).length).toBeGreaterThan(0);
    expect(rawKeyTexts(zhDrawer)).toEqual([]);
    cleanup();

    // en-US
    await act(async () => {
      await i18n.changeLanguage('en-US');
    });
    await renderApp();
    const enDrawer = await openPreferencesDrawer();
    const en = within(enDrawer);
    expect(en.getAllByText(/theme|appearance|mode/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/light/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/dark/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/system|follow/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/accent|color/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/density|compact|comfort/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/motion|animation|transition|effect/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/reset|restore|default/i).length).toBeGreaterThan(0);
    expect(rawKeyTexts(enDrawer)).toEqual([]);
    cleanup();

    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
  });
});
