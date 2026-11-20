/**
 * @vitest-environment jsdom
 *
 * 主题派生回归锚点（REQ-20261119-032108 契约②）：
 * - 历史 API 兼容：静态 palette 导出仍为 dark 缺省子集、语义映射引用同一；
 * - 双主题派生：ThemeProvider 消费 core settings.theme，经 resolvePalette 派生，
 *   dark→DARK_NEUTRALS / light→LIGHT_NEUTRALS（引用同一性），切换即时刷新；
 * - StatusBar barStyle 联动（barStyleFor）。
 * 装配：jsdom + react-dom 渲染探针（ThemeProvider/useAppTheme 为纯 React，无 RN 依赖）；
 * async-storage 内存桩（theme → useStore → utils/storage import 链兜底）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DARK_NEUTRALS,
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

import {
  ACCENTS,
  ACCENT_ORDER,
  palette,
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
  // 归位缺省主题（dark），用例间互不污染
  useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'dark' } });
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
    expect(palette).toEqual({
      bg: DARK_NEUTRALS.bg,
      surface: DARK_NEUTRALS.surface,
      elevated: DARK_NEUTRALS.elevated,
      text: DARK_NEUTRALS.text,
      text2: DARK_NEUTRALS.text2,
      muted: DARK_NEUTRALS.muted,
      border: DARK_NEUTRALS.border,
    });
    expect(palette.bg).toBe('#14161a');
    expect(palette.muted).toBe('#8b929e');
    // resolvePalette 分支：引用同一性（dark/light 各自返回 core 常量对象本身）
    expect(resolvePalette('dark')).toBe(DARK_NEUTRALS);
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

    // settings.theme → light：zustand 通知 → ThemeProvider 重派生 → 探针即时刷新
    await act(async () => {
      useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'light' } });
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
      useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'dark' } });
      await Promise.resolve();
    });
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
  });
});
