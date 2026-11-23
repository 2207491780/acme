/**
 * REQ-20261121-043502「外观能力包」AC-3web/AC-5~AC-9/AC-11：web 偏好抽屉 v3 契约用例。
 *
 * 装配照范本 web/tests/REQ20261121010725-preferencesDrawer.test.tsx（整棵 App + AntApp +
 * HashRouter + 会话/me 播种 + 浏览器桩；ThemedApp 以 useStore 订阅 settings 复刻
 * main.tsx <ConfigProvider theme={buildTheme(settings)}> 消费点）。
 *
 * 交互口径（pitfalls #7/#8/#9/#10 + THEME-1 先例）：
 *   - Segmented 分组化定位：以分组标签（圆角/宽度）向上找最近 .ant-segmented，避免
 *     主题模式/圆角/内容宽度/密度多组并存后的文本跨组误命中；
 *   - 语义色三行按「行标签 + 最近含 ColorPicker 触发器祖先」行级作用域限定；
 *   - Switch/Popconfirm/文案断言宽容正则；Drawer 开合按 .ant-drawer-open 数据驱动断言。
 *
 * v3 视觉面断言分层（jsdom 不算 CSS，样式面走源断言 + data-attribute/inline style 实测）：
 *   - 灰度/色弱：根容器 data-attribute（契约确认记录④「以 data-attribute 挂根」）+
 *     global.css 源（grayscale / SVG 矩阵 data-uri / 双开 filter 链）；
 *   - 内容宽度 compact：内容包裹层宽度锚（data-attribute / inline max-width / 类名）+
 *     global.css max-width 规则；
 *   - 圆角/语义色：buildTheme token（token.borderRadius=resolveRadiusTokens 档位值、
 *     colorSuccess/colorWarning/colorError）+ themeSnapshot 语义色同步。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act } from 'react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import { buildTheme, themeSnapshot } from '../src/theme';
import {
  DEFAULT_PREFERENCES,
  TONES,
  i18n,
  resolveRadiusTokens,
  useAuthStore,
  useMeStore,
  type Settings,
  type ThemePreferences,
} from '@acme/core';

// testTimeout 45s（TL 门禁前置微修：满载并行时 AC-7 用例曾抖到 21s 超 20s 时限，隔离实测 14.3s；
// 按仓库抖动口径保余量，对齐 TENANT-1 packagesPage 45s / THEME-1 drawer 单例 30s 先例，pitfalls #15）
// testTimeout 140s（TL round-2 预算放宽，判定链闭环后披露制：theme4-idle-rerun.log 失败用例闲置实测
// 最大 55.5s；新预算=max(实测×2.5, 现预算×2)=138710→140s，重交互用例 ≥60s 口径；断言/行为零改动）
vi.setConfig({ testTimeout: 140000 });
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const BLOB_KEY = 'acme.frontend.ops.v1'; // 契约钉死（packages/core/src/constants.ts）

// ————— 浏览器桩：matchMedia（桌面断点放行 + prefers-color-scheme 捕获）+ ResizeObserver —————

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
    if (url.pathname.includes('/system/me')) return envelope(mePayload(['system:user:list']));
    return envelope(null);
  }));
  await useMeStore.getState().load();
  window.location.hash = '#/';
  render(<AntApp><HashRouter><ThemedApp /></HashRouter></AntApp>);
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

type BlobPrefsLike = Record<string, unknown> & { semanticColors?: Record<string, unknown> };

/** blob 内 preferences 段（容忍 zustand persist 信封包裹），供结构化断言（semanticColors 键序无关）。 */
const blobPrefs = (): BlobPrefsLike | null => {
  const raw = window.localStorage.getItem(BLOB_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const holder = (parsed['state'] && typeof parsed['state'] === 'object'
      ? parsed['state'] : parsed) as Record<string, unknown>;
    const settings = holder['settings'] as Record<string, unknown> | undefined;
    return (settings?.['preferences'] as BlobPrefsLike | undefined) ?? null;
  } catch {
    return null;
  }
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

// ————— 交互工具（分组化 Segmented / Switch / 行级语义色） —————

/**
 * Segmented 项点击（THEME-1 渲染树实测口径）：项 = label.ant-segmented-item +
 * 隐藏 input[type=radio]；jsdom 不把 label 点击转发给 radio → 直接点击 input 提交。
 * 与 THEME-1 全抽屉扫描版的差异：仅在本分组 .ant-segmented 内匹配（多组并存防跨组误命中）。
 */
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

/** 以分组标签为锚向上找最近 .ant-segmented（分组标签与控件同行，最近祖先即本组）。 */
const segmentedGroupNear = (scope: HTMLElement, groupLabel: RegExp): HTMLElement => {
  const labelEl = within(scope).queryAllByText(groupLabel)[0];
  expect(labelEl, `抽屉内应存在分组标签 ${groupLabel}`).toBeTruthy();
  let node: HTMLElement | null = labelEl as HTMLElement;
  for (let depth = 0; depth < 6 && node; depth += 1) {
    const group = node.querySelector<HTMLElement>('.ant-segmented');
    if (group) return group;
    node = node.parentElement;
  }
  let sibling: Element | null = (labelEl as HTMLElement).nextElementSibling;
  while (sibling) {
    const group = sibling.classList.contains('ant-segmented')
      ? (sibling as HTMLElement)
      : sibling.querySelector<HTMLElement>('.ant-segmented');
    if (group) return group;
    sibling = sibling.nextElementSibling;
  }
  expect.fail(`分组标签 ${groupLabel} 附近未找到 Segmented 分组`);
};

/**
 * 按分组文案定位开关（THEME-1 口径 + 043502 泊位化）：灰度/色弱开关可能共享外层容器，
 * 按祖先链「最近命中标签深度」择优（深度平手取 DOM 序在前者），避免把灰度开关误当色弱开关。
 */
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

/** 行级作用域：以行标签为锚向上找最近含 anchor 选择器的祖先（语义色行=标签+色板+取色+清除同行）。 */
const rowScope = (scope: HTMLElement, rowLabel: RegExp, anchor: string): HTMLElement | null => {
  const labelEl = within(scope).queryAllByText(rowLabel)[0];
  if (!labelEl) return null;
  let node: HTMLElement | null = labelEl as HTMLElement;
  for (let depth = 0; depth < 6 && node; depth += 1) {
    if (node.querySelector(anchor)) return node;
    node = node.parentElement;
  }
  return null;
};

const rgbToHex = (rgb: string): string | null => {
  const m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(rgb);
  if (!m) return null;
  return `#${[m[1], m[2], m[3]].map((n) => Number(n).toString(16).padStart(2, '0')).join('')}`;
};

/** 从色板圆点元数据（style/title/aria-label）提取 6 位 hex。 */
const swatchHex = (el: HTMLElement): string | null => {
  const style = (el.getAttribute('style') ?? '').toLowerCase();
  const meta = `${style} ${(el.getAttribute('title') ?? '').toLowerCase()} ${(el.getAttribute('aria-label') ?? '').toLowerCase()}`;
  const hex = /#[0-9a-f]{6}\b/.exec(meta)?.[0];
  if (hex) return hex;
  const rgb = /rgba?\([^)]+\)/.exec(style)?.[0];
  return rgb ? rgbToHex(rgb) : null;
};

/**
 * 行内预设色板圆点：排除 ColorPicker 触发器（其背景是当前值，非预设），
 * 取元数据 hex ≠ 基线（excludedHex）的第一个圆点，并回读其 hex。
 */
const findPresetSwatch = (scope: HTMLElement, excludedHex: string): { el: HTMLElement; hex: string } | null => {
  const excluded = excludedHex.toLowerCase();
  const candidates = Array.from(scope.querySelectorAll<HTMLElement>('[style], [title], [aria-label]'))
    .filter((el) => !el.closest('.ant-color-picker-trigger') && !el.classList.contains('ant-color-picker-trigger'));
  for (const el of candidates) {
    const hex = swatchHex(el);
    if (hex && hex.toLowerCase() !== excluded) return { el, hex };
  }
  return null;
};

/**
 * 行级自定义取色：按行标签实时重定位行（防上一步操作重渲染后捕获节点失效），行内
 * ColorPicker 触发器 → 弹层面板（.ant-popover.ant-color-picker，THEME-1 渲染树实测）→
 * hex 输入 change 即提交 + Enter/blur 兜底。
 */
const applyRowColor = async (drawer: HTMLElement, rowLabel: RegExp, hex: string): Promise<void> => {
  const row = rowScope(drawer, rowLabel, '.ant-color-picker-trigger');
  expect(row, `语义色行 ${rowLabel} 应存在（含取色入口）`).toBeTruthy();
  const trigger = (row as HTMLElement).querySelector<HTMLElement>('.ant-color-picker-trigger');
  expect(trigger, '语义色行应提供自定义取色入口（ColorPicker）').toBeTruthy();
  await act(async () => {
    fireEvent.click(trigger as HTMLElement);
  });
  await waitFor(() => {
    expect(document.querySelector('.ant-popover.ant-color-picker, .ant-color-picker')).toBeTruthy();
  });
  const panel = (document.querySelector('.ant-popover.ant-color-picker, .ant-color-picker') ?? document) as HTMLElement;
  const input = panel.querySelector<HTMLInputElement>('.ant-color-picker-hex-input input');
  expect(input, 'ColorPicker 面板应提供 hex 输入框').toBeTruthy();
  await act(async () => {
    fireEvent.change(input as HTMLInputElement, { target: { value: hex } });
    fireEvent.keyDown(input as HTMLElement, { key: 'Enter', code: 'Enter' });
    (input as HTMLInputElement).blur();
  });
};

/** 行内清除/恢复默认入口（按行标签实时重定位；文本或 aria-label 宽容匹配）。 */
const rowClearButton = (drawer: HTMLElement, rowLabel: RegExp): HTMLElement | null => {
  const row = rowScope(drawer, rowLabel, 'button');
  if (!row) return null;
  return within(row).queryAllByRole('button').find((button) => {
    const meta = `${button.textContent ?? ''} ${button.getAttribute('aria-label') ?? ''} ${button.getAttribute('title') ?? ''}`;
    return /清除|恢复默认|重置|clear|reset/i.test(meta);
  }) ?? null;
};

// ————— v3 视觉面：根容器 data-attribute / 内容宽度锚 / global.css 源 —————

type SurfaceMark = { name: string; value: string; host: HTMLElement };

/** 全文档扫描 data-* 属性（名+值）命中外观面关键词的标记（契约④：以 data-attribute 挂根）。 */
const surfaceMarks = (): SurfaceMark[] => {
  const hosts: Element[] = [document.documentElement, ...Array.from(document.querySelectorAll('*'))];
  const marks: SurfaceMark[] = [];
  for (const host of hosts) {
    for (const attr of Array.from(host.attributes ?? [])) {
      if (!attr.name.startsWith('data-')) continue;
      const hay = `${attr.name} ${attr.value}`.toLowerCase();
      if (/gray|weak|灰|色弱/.test(hay)) {
        marks.push({ name: attr.name, value: attr.value, host: host as HTMLElement });
      }
    }
  }
  return marks;
};

/** 标记处于「开」态：属性存在且值非显式关闭（空值按存在即开）。 */
const markIsOn = (mark: SurfaceMark): boolean =>
  !/^(false|none|off|0)$/.test(mark.value.trim().toLowerCase());

const grayMarks = (): SurfaceMark[] =>
  surfaceMarks().filter((m) => /gray|灰/.test(`${m.name} ${m.value}`.toLowerCase()) && markIsOn(m));

const weakMarks = (): SurfaceMark[] =>
  surfaceMarks().filter((m) => /weak|色弱|盲/.test(`${m.name} ${m.value}`.toLowerCase()) && markIsOn(m));

/** 内容区（Layout 内容包裹层）宽度锚：inline max-width / data-* 宽度属性 / compact 类名。 */
const maxWidthAnchor = (): string | null => {
  const content = document.querySelector('main.ant-layout-content') ?? document.body;
  const nodes: Element[] = [content, ...Array.from(content.querySelectorAll('*'))];
  for (const el of nodes) {
    const style = el.getAttribute('style') ?? '';
    if (/max-width\s*:/i.test(style)) return `inline:${el.tagName}:${style}`;
    for (const attr of Array.from(el.attributes)) {
      if (!attr.name.startsWith('data-')) continue;
      if (/width|fluid|compact|narrow|center/i.test(`${attr.name} ${attr.value}`)) {
        return `${attr.name}=${attr.value}`;
      }
    }
    if (/\b(compact|narrow|centered)\b/i.test(el.getAttribute('class') ?? '')) {
      return `class:${el.getAttribute('class')}`;
    }
  }
  return null;
};

/** global.css 源（视觉单源镜像；灰度/色弱 filter 与内容宽度规则按契约落在此文件）。
 *  jsdom 环境 import.meta.url 非 file scheme → 按 cwd（web 包根）定位。 */
const globalCssPath = ['./src/styles/global.css', '../src/styles/global.css']
  .map((relative) => resolve(process.cwd(), relative))
  .find((candidate) => existsSync(candidate));
const globalCss = (): string => readFileSync(globalCssPath as string, 'utf8');

// ————— i18n 裸 key 扫描（THEME-1 口径） —————

const RAW_KEY_RE = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$/;
const rawKeyTexts = (scope: HTMLElement): string[] =>
  Array.from(scope.querySelectorAll('*'))
    .map((el) => (el.childElementCount === 0 ? (el.textContent ?? '').trim() : ''))
    .filter((text) => RAW_KEY_RE.test(text));

// ————— 用例 —————

beforeEach(async () => {
  stubBrowserGaps();
  await act(async () => {
    await i18n.changeLanguage('zh-CN');
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

describe('REQ20261121043502 外观组四控件（AC-5）', () => {
  it('should_apply_and_persist_each_appearance_control_when_changed', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();

    // 基线（v3 默认）
    expect(currentPrefs().radius).toBe('medium');
    expect(currentPrefs().contentWidth).toBe('fluid');
    expect(currentPrefs().grayMode).toBe(false);
    expect(currentPrefs().weakMode).toBe(false);

    // ① 圆角档位 full（胶囊）：prefs + buildTheme token.borderRadius=档位映射值 + 持久化
    // （每次点击前实时重定位分组，防上一步重渲染后捕获节点失效）
    await pickInGroup(segmentedGroupNear(drawer, /圆角/), /胶囊|全圆角|(^|\s)全(\s|$)/);
    await waitFor(() => expect(currentPrefs().radius).toBe('full'));
    expect(currentThemeConfig().token?.borderRadius).toBe(resolveRadiusTokens('full'));
    expectBlobToContain('"radius":"full"');

    // 再切 small 档：token 跟随档位函数（small < medium 基线）
    await pickInGroup(segmentedGroupNear(drawer, /圆角/), /小圆角|较小|(^|\s)小(\s|$)/);
    await waitFor(() => expect(currentPrefs().radius).toBe('small'));
    expect(currentThemeConfig().token?.borderRadius).toBe(resolveRadiusTokens('small'));
    expect(resolveRadiusTokens('small')).toBeLessThan(resolveRadiusTokens('medium'));

    // ② 内容宽度 compact：prefs + 内容包裹层宽度锚（data-attribute/inline max-width）+ CSS 规则 + 持久化
    await pickInGroup(segmentedGroupNear(drawer, /宽度/), /定宽居中|居中|紧凑|适中|固定|窄|compact/i);
    await waitFor(() => expect(currentPrefs().contentWidth).toBe('compact'));
    await waitFor(() =>
      expect(maxWidthAnchor(), 'compact 后内容包裹层应携带宽度锚（data-attribute/inline max-width/类名）').toBeTruthy());
    expect(/max-width\s*:\s*\d{3,}px/.test(globalCss()), 'global.css 应存在 compact 内容宽度 max-width 规则').toBe(true);
    expectBlobToContain('"contentWidth":"compact"');

    // ③ 灰度 Switch：prefs + 根容器 data-attribute + 持久化
    const graySwitch = labeledSwitch(drawer, /灰度/);
    expect(graySwitch, '灰度开关应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(graySwitch as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().grayMode).toBe(true));
    await waitFor(() =>
      expect(grayMarks().length, '开启灰度后根容器应出现灰度 data-attribute').toBeGreaterThan(0));
    expectBlobToContain('"grayMode":true');

    // ④ 色弱 Switch：prefs + 根容器 data-attribute + 持久化
    const weakSwitch = labeledSwitch(drawer, /色弱/);
    expect(weakSwitch, '色弱开关应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(weakSwitch as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().weakMode).toBe(true));
    await waitFor(() =>
      expect(weakMarks().length, '开启色弱后根容器应出现色弱 data-attribute').toBeGreaterThan(0));
    expectBlobToContain('"weakMode":true');
  });
});

describe('REQ20261121043502 灰度×色弱叠加（AC-6）', () => {
  it('should_stack_gray_and_weak_filters_when_both_enabled', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();

    const graySwitch = labeledSwitch(drawer, /灰度/);
    const weakSwitch = labeledSwitch(drawer, /色弱/);
    expect(graySwitch && weakSwitch, '灰度/色弱开关应同时存在（可独立开启）').toBeTruthy();

    // 开灰度 → 灰度标记出现（DOM 副作用 waitFor：store 更新与根容器 dataset 写入异步落盘）
    await act(async () => {
      fireEvent.click(graySwitch as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().grayMode).toBe(true));
    await waitFor(() => expect(grayMarks().length, '开启灰度后根容器应出现灰度 data-attribute').toBeGreaterThan(0));
    expect(weakMarks().length, '色弱未开时不应有色弱标记').toBe(0);

    // 再开色弱 → 两标记并存且作用于同一根容器（互不覆盖）
    await act(async () => {
      fireEvent.click(labeledSwitch(drawer, /色弱/) as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().weakMode).toBe(true));
    await waitFor(() => {
      const grayAfter = grayMarks();
      const weakAfter = weakMarks();
      expect(grayAfter.length, '双开时灰度标记应保留（不被色弱覆盖）').toBeGreaterThan(0);
      expect(weakAfter.length, '双开时色弱标记应出现').toBeGreaterThan(0);
      expect(
        grayAfter.some((g) => weakAfter.some((w) => g.host === w.host || g.host.contains(w.host) || w.host.contains(g.host))),
        `灰度与色弱标记应挂同一根容器（CSS filter 链叠加，确认记录④）；gray=${JSON.stringify(grayAfter.map((m) => [m.name, m.value, m.host.tagName]))} weak=${JSON.stringify(weakAfter.map((m) => [m.name, m.value, m.host.tagName]))}`,
      ).toBe(true);
    });

    // CSS 面：灰度 grayscale + 色弱 SVG 矩阵（data-uri feColorMatrix）+ 单条 filter 链（先灰度后色弱）
    const css = globalCss();
    expect(/grayscale\s*\(/i.test(css), 'global.css 应定义灰度 filter（grayscale）').toBe(true);
    expect(
      /(data:image\/svg\+xml|feColorMatrix)/i.test(css),
      'global.css 应以 SVG 矩阵（data-uri feColorMatrix）定义色弱 filter',
    ).toBe(true);
    const chainDecls = css.match(/filter\s*:[^;{}]*grayscale[^;{}]*/gi) ?? [];
    expect(
      chainDecls.some((decl) => /(url\s*\(|matrix|feColorMatrix)/i.test(decl)),
      `双开叠加应为单条 filter 链（先灰度后色弱），实际 grayscale 声明：${chainDecls.join(' | ')}`,
    ).toBe(true);

    // 反向互不覆盖：关灰度 → 灰度标记消失、色弱标记保留
    await act(async () => {
      fireEvent.click(labeledSwitch(drawer, /灰度/) as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().grayMode).toBe(false));
    await waitFor(() => {
      expect(grayMarks().length, '关闭灰度后灰度标记应消失').toBe(0);
      expect(weakMarks().length, '关闭灰度不影响色弱标记').toBeGreaterThan(0);
    });
  });
});

describe('REQ20261121043502 语义色覆盖组（AC-7）', () => {
  it('should_apply_reset_semantic_colors_when_picker_operated', async () => {
    await renderApp();
    const drawer = await openPreferencesDrawer();
    const ui = within(drawer);

    // 语义色组三行（成功/警告/危险）；每步实时重定位行（防上一步重渲染后节点失效）
    expect(ui.getAllByText(/语义色|语义/).length).toBeGreaterThan(0);

    // ① 预设：成功行选非基线预设圆点 → prefs + colorSuccess + 持久化
    const successRow = rowScope(drawer, /成功/, '.ant-color-picker-trigger');
    const successPreset = successRow ? findPresetSwatch(successRow, TONES.success) : null;
    expect(successPreset, '成功行应提供预设色板圆点（TONES 现值+近似候选）').toBeTruthy();
    const successHex = (successPreset as { el: HTMLElement; hex: string }).hex;
    await act(async () => {
      fireEvent.click((successPreset as { el: HTMLElement; hex: string }).el);
    });
    await waitFor(() =>
      expect(String(currentPrefs().semanticColors?.success ?? '').toLowerCase()).toBe(successHex.toLowerCase()));
    expect(String(currentThemeConfig().token?.colorSuccess ?? '').toLowerCase()).toBe(successHex.toLowerCase());
    await waitFor(() => {
      const persisted = (blobPrefs()?.semanticColors as Record<string, unknown> | undefined)?.success;
      expect(String(persisted ?? '').toLowerCase()).toBe(successHex.toLowerCase());
    });

    // ② 自定义：警告行 ColorPicker 输入合法 hex → prefs + colorWarning
    const warningCustom = '#7c3aed';
    await applyRowColor(drawer, /警告/, warningCustom);
    await waitFor(() =>
      expect(String(currentPrefs().semanticColors?.warning ?? '').toLowerCase()).toBe(warningCustom.toLowerCase()));
    expect(String(currentThemeConfig().token?.colorWarning ?? '').toLowerCase()).toBe(warningCustom.toLowerCase());

    // ③ 清除：警告行清除 → 回默认（colorWarning=TONES.warning）
    const warningClear = rowClearButton(drawer, /警告/);
    expect(warningClear, '警告行应提供清除入口').toBeTruthy();
    await act(async () => {
      fireEvent.click(warningClear as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().semanticColors?.warning ?? null).toBeNull());
    expect(currentThemeConfig().token?.colorWarning).toBe(TONES.warning);

    // ④ 危险行：预设生效 → 清除回默认（colorError=TONES.danger）
    const dangerRow = rowScope(drawer, /危险|错误/, '.ant-color-picker-trigger');
    const dangerPreset = dangerRow ? findPresetSwatch(dangerRow, TONES.danger) : null;
    expect(dangerPreset, '危险行应提供预设色板圆点').toBeTruthy();
    const dangerHex = (dangerPreset as { el: HTMLElement; hex: string }).hex;
    await act(async () => {
      fireEvent.click((dangerPreset as { el: HTMLElement; hex: string }).el);
    });
    await waitFor(() =>
      expect(String(currentPrefs().semanticColors?.danger ?? '').toLowerCase()).toBe(dangerHex.toLowerCase()));
    expect(String(currentThemeConfig().token?.colorError ?? '').toLowerCase()).toBe(dangerHex.toLowerCase());
    const dangerClear = rowClearButton(drawer, /危险|错误/);
    expect(dangerClear, '危险行应提供清除入口').toBeTruthy();
    await act(async () => {
      fireEvent.click(dangerClear as HTMLElement);
    });
    await waitFor(() => expect(currentPrefs().semanticColors?.danger ?? null).toBeNull());
    expect(currentThemeConfig().token?.colorError).toBe(TONES.danger);

    // 持久化终态：success=预设 hex、warning/danger=null（键序无关的结构化断言）
    await waitFor(() => {
      const sem = (blobPrefs()?.semanticColors ?? {}) as Record<string, unknown>;
      expect(String(sem['success'] ?? '').toLowerCase()).toBe(successHex.toLowerCase());
      expect(sem['warning'] ?? null).toBeNull();
      expect(sem['danger'] ?? null).toBeNull();
    });
  });
});

describe('REQ20261121043502 buildTheme/themeSnapshot v3 消费（AC-3web）', () => {
  it('should_build_theme_with_full_radius_and_custom_warning_when_v3_settings', async () => {
    // store 直驱（不渲染 App）：radius=full + warning 自定义（契约 AC-3 buildTheme 面）
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          radius: 'full',
          semanticColors: { success: null, warning: '#d4a017', danger: null },
        },
      },
    });
    await waitFor(() => expect(currentPrefs().radius).toBe('full'));

    // token.borderRadius 生效胶囊圆角（> medium 基线）
    const token = currentThemeConfig().token ?? {};
    expect(token.borderRadius).toBe(resolveRadiusTokens('full'));
    expect(resolveRadiusTokens('full')).toBeGreaterThan(resolveRadiusTokens('medium'));
    // colorWarning=自定义值；未覆盖项回 TONES 基线（默认项不变）
    expect(String(token.colorWarning ?? '').toLowerCase()).toBe('#d4a017');
    expect(token.colorSuccess).toBe(TONES.success);
    expect(token.colorError).toBe(TONES.danger);

    // themeSnapshot 同步语义色覆盖（图表面，TL 钉死：themeSnapshot 同步）
    const snapshot = JSON.stringify(
      themeSnapshot(useStore.getState().state.settings as unknown as Settings),
    ).toLowerCase();
    expect(snapshot, 'themeSnapshot 应同步 warning 自定义值').toContain('#d4a017');
    expect(snapshot, 'themeSnapshot 未覆盖语义色应回 TONES 基线').toContain(TONES.danger.toLowerCase());
  });
});

describe('REQ20261121043502 恢复默认含 v3（AC-8）', () => {
  it('should_reset_v3_fields_when_reset_confirmed', async () => {
    // 预置非默认偏好（v2 字段 + v3 新字段全量非默认）
    useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: {
        preferences: {
          ...DEFAULT_PREFERENCES,
          mode: 'dark', accent: 'emerald', customAccent: '#b08968', density: 'compact', motionEnabled: false,
          radius: 'full', grayMode: true, weakMode: true, contentWidth: 'compact',
          semanticColors: { success: '#22c55e', warning: '#c2410c', danger: null },
        },
      },
    });
    await renderApp();
    const drawer = await openPreferencesDrawer();
    expect(currentPrefs().radius).toBe('full');
    expect(currentPrefs().grayMode).toBe(true);
    expect(currentPrefs().contentWidth).toBe('compact');

    const resetButton = within(drawer).getAllByRole('button', { name: /恢复默认|重置/ })[0];
    expect(resetButton, '恢复默认入口应存在').toBeTruthy();
    await act(async () => {
      fireEvent.click(resetButton as HTMLElement);
    });

    // 二次确认（Popconfirm，THEME-1 口径）
    await waitFor(() => expect(document.querySelector('.ant-popover:not(.ant-popover-hidden)')).toBeTruthy());
    const popover = document.querySelector('.ant-popover:not(.ant-popover-hidden)') as HTMLElement;
    const confirmButton = within(popover).getAllByRole('button')
      .find((b) => /确|重置|OK|恢复/i.test(b.textContent ?? ''));
    expect(confirmButton, '确认弹窗应提供确认按钮').toBeTruthy();
    await act(async () => {
      fireEvent.click(confirmButton as HTMLElement);
    });

    // v3 新字段全部回默认（含 v2 字段）并即时生效
    await waitFor(() => expect(currentPrefs()).toEqual({ ...DEFAULT_PREFERENCES }));
    expectBlobToContain('"radius":"medium"');
    expectBlobToContain('"grayMode":false');
    expectBlobToContain('"contentWidth":"fluid"');
    await waitFor(() => {
      expect(blobPrefs()?.semanticColors ?? null).toEqual({ success: null, warning: null, danger: null });
    });
    expect(currentThemeConfig().token?.borderRadius).toBe(resolveRadiusTokens('medium'));
  });
});

describe('REQ20261121043502 抽屉 v3 文案双语覆盖（AC-9）', () => {
  it('should_cover_v3_strings_in_zh_and_en', async () => {
    // zh-CN：圆角/内容宽度/灰度/色弱/语义色 + 控件形（≥3 Segmented 组 + ≥3 Switch）+ 无裸 key
    await renderApp();
    const zhDrawer = await openPreferencesDrawer();
    const zh = within(zhDrawer);
    expect(zh.getAllByText(/圆角/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/内容宽度|宽度/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/灰度/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/色弱/).length).toBeGreaterThan(0);
    expect(zh.getAllByText(/语义色|语义/).length).toBeGreaterThan(0);
    expect(zhDrawer.querySelectorAll('.ant-segmented').length).toBeGreaterThanOrEqual(3);
    expect(zh.queryAllByRole('switch').length).toBeGreaterThanOrEqual(3);
    expect(rawKeyTexts(zhDrawer), 'zh 抽屉不应出现裸 i18n key').toEqual([]);
    cleanup();

    // en-US：同样锚点 + 无裸 key
    await act(async () => {
      await i18n.changeLanguage('en-US');
    });
    await renderApp();
    const enDrawer = await openPreferencesDrawer();
    const en = within(enDrawer);
    expect(en.getAllByText(/radius|corner/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/width|fluid|compact/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/gray|greyscale|grayscale/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/weak|color.?blind|vision/i).length).toBeGreaterThan(0);
    expect(en.getAllByText(/semantic/i).length).toBeGreaterThan(0);
    expect(rawKeyTexts(enDrawer), 'en 抽屉不应出现裸 i18n key').toEqual([]);
    cleanup();

    await act(async () => {
      await i18n.changeLanguage('zh-CN');
    });
  });
});

describe('REQ20261121043502 ui:guide 产物（AC-11）', () => {
  it('should_regenerate_style_guide_with_v3_tokens', () => {
    // 校验页源断言（宽容匹配）：现产物无中文「圆角/语义色」锚 → 重生成后必含。
    // jsdom 环境 import.meta.url 非 file scheme，按 cwd（web 包根）向上定位产物。
    const guidePath = ['../ui-spec/style-guide.html', '../../ui-spec/style-guide.html']
      .map((relative) => resolve(process.cwd(), relative))
      .find((candidate) => existsSync(candidate));
    expect(guidePath, `ui:guide 产物应存在（cwd=${process.cwd()}）`).toBeTruthy();
    const guide = readFileSync(guidePath as string, 'utf8');
    expect(guide, '校验页应含新圆角档位锚（圆角/胶囊/全圆角）').toMatch(/胶囊|全圆角|圆角/);
    expect(guide, '校验页应含语义色覆盖锚（语义色/语义覆盖/semantic override）')
      .toMatch(/语义色|语义覆盖|semantic\s*(color|override)/i);
  });
});
