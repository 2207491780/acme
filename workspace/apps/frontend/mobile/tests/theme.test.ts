/**
 * @vitest-environment jsdom
 *
 * 主题派生回归锚点（REQ-20261119-032108 契约②；THEME-3 REQ-20261121-200415 适配）：
 * - resolvePalette 分支引用同一性（dark→DARK_NEUTRALS / light→LIGHT_NEUTRALS）；
 *   THEME-3 ⑧：静态 palette/onAccent 历史导出已删除，不再断言；
 * - 双主题派生：ThemeProvider 消费 core preferences.mode（legacy patch theme→mode 语义不变），
 *   dark→DARK_NEUTRALS / light→LIGHT_NEUTRALS（引用同一性），切换即时刷新；
 * - StatusBar barStyle 联动（barStyleFor）。
 * 装配：jsdom + react-dom 渲染探针；async-storage 内存桩 + react-native 替身
 * （THEME-3 ⑦：theme.ts 经 useColorScheme 解析 system，vitest 下需打桩）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DARK_NEUTRALS,
  DEFAULT_PREFERENCES,
  LIGHT_NEUTRALS,
  TONES,
  PLATFORM_COLORS,
  ENV_COLORS,
  HEALTH_COLORS,
  HEALTH_LABELS,
  resolvePalette,
} from '@acme/core';
import { useStore } from '../src/store/useStore';

// async-storage 以内存桩替代（theme.ts → store/useStore → utils/storage import 链兜底）
vi.mock('@react-native-async-storage/async-storage', () => {
  const store = new Map<string, string>();
  const api = {
    getItem: vi.fn(async (key: string) => store.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn(async (key: string) => {
      store.delete(key);
    }),
  };
  return { default: api, AsyncStorage: api };
});

// react-native 替身（THEME-3 ⑦：theme.ts 经 useColorScheme 解析 system 三态）
vi.mock('react-native', () => ({
  useColorScheme: () => 'dark',
  useWindowDimensions: () => ({ width: 390, height: 844, scale: 2, fontScale: 1 }),
  StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
  Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
}));

import {
  ACCENTS,
  ACCENT_ORDER,
  TONE_COLOR,
  PLATFORM_COLOR,
  ENV_COLOR,
  HEALTH_COLOR,
  HEALTH_LABEL,
  ThemeProvider,
  useAppTheme,
  barStyleFor,
  type AppThemeValue,
} from '../src/theme';

// React 18 act 环境
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLElement;
let root: Root | undefined;
let captured: AppThemeValue | undefined;

function Probe() {
  captured = useAppTheme();
  return null;
}

async function renderProbe(): Promise<void> {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root!.render(createElement(ThemeProvider, null, createElement(Probe)));
  });
}

beforeEach(() => {
  captured = undefined;
  // 归位缺省主题（dark），用例间互不污染（THEME-3 ⑧：patch 走 preferences.mode）
  useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' } } });
});

afterEach(() => {
  if (root) {
    act(() => {
      root!.unmount();
    });
    root = undefined;
  }
  container?.remove();
});

describe('mobile theme derivation（历史 API 兼容）', () => {
  it('should_derive_palette_from_core_dark_neutrals', () => {
    // THEME-3 ⑧：静态 palette 导出已删除；本用例保留 resolvePalette 分支引用同一性断言
    expect(resolvePalette('dark')).toBe(DARK_NEUTRALS);
    expect(resolvePalette('dark').bg).toBe('#14161a');
    expect(resolvePalette('dark').muted).toBe('#8b929e');
    expect(resolvePalette('light')).toBe(LIGHT_NEUTRALS);
  });

  it('should_keep_historical_semantic_maps', () => {
    expect(ACCENTS.cyan).toBe('#5aa7a0');
    // REQ-20261121-010725：core ACCENTS 6 → 12（确认记录⑤/变更清单），mobile 复用 core 单源同步扩容
    // （与 core/web theme.test 适配同口径：新增键值 = TL 钉死事实）
    expect(ACCENT_ORDER).toHaveLength(12);
    expect(ACCENTS.emerald).toBe('#58aa82');
    expect(ACCENTS.sky).toBe('#6aa8cf');
    expect(ACCENTS.coral).toBe('#cd7a63');
    expect(ACCENTS.ruby).toBe('#c56d6d');
    expect(ACCENTS.bronze).toBe('#b08968');
    expect(ACCENTS.slate).toBe('#8a9bb0');
    expect(TONE_COLOR).toBe(TONES);
    expect(PLATFORM_COLOR).toBe(PLATFORM_COLORS);
    expect(ENV_COLOR).toBe(ENV_COLORS);
    expect(HEALTH_COLOR).toBe(HEALTH_COLORS);
    expect(HEALTH_LABEL).toBe(HEALTH_LABELS);
    expect(TONE_COLOR.success).toBe('#6fa287');
    expect(PLATFORM_COLOR.desktop).toBe('#9188c9');
  });
});

describe('mobile theme（REQ-20261119-032108 双主题派生）', () => {
  it('should_derive_dark_palette_when_theme_dark', async () => {
    await renderProbe();

    expect(captured).toBeDefined();
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
    expect(captured!.palette.bg).toBe('#14161a');
    expect(captured!.palette.surface).toBe('#1d2127');
    expect(barStyleFor(captured!.theme)).toBe('light-content');
  });

  it('should_derive_light_palette_and_refresh_when_theme_switches', async () => {
    await renderProbe();
    expect(captured!.theme).toBe('dark');

    // patch preferences.mode → light：zustand 通知 → ThemeProvider 重派生 → 探针即时刷新
    // （THEME-3 ⑧：投影键删除，patch 一律走 preferences）
    await act(async () => {
      useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'light' } } });
      await Promise.resolve();
    });

    expect(captured!.theme).toBe('light');
    expect(captured!.palette).toBe(LIGHT_NEUTRALS);
    expect(captured!.palette.bg).toBe('#f7f8f9');
    expect(captured!.palette.surface).toBe('#ffffff');
    expect(captured!.palette.text).toBe('#1c2128');
    expect(barStyleFor(captured!.theme)).toBe('dark-content');

    // 切回 dark：引用回到 DARK_NEUTRALS 本体
    await act(async () => {
      useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES, mode: 'dark' } } });
      await Promise.resolve();
    });
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
  });
});
