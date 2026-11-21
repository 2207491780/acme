/**
 * @vitest-environment jsdom
 *
 * REQ-20261121-200415（THEME-3）AC-9：mobile 主题三态跟随系统契约用例。
 *
 * 表面 = 契约确认记录⑦ + AC-9：
 *   三态 mode（'system'|'light'|'dark'）：system 经 react-native useColorScheme 解析系统色，
 *   系统切换即时生效；手动选 light/dark 脱离 system（系统事件不再改变用户选择）。
 *
 * 装配照 mobile/tests/theme.test.ts 范本：jsdom + react-dom 渲染探针（ThemeProvider/useAppTheme），
 *   async-storage 内存桩兜底 useStore 持久化链；react-native 以替身模块打桩提供受控
 *   useColorScheme（scheme 容器可变，重渲染即模拟系统色切换）。mode 写入走 reducer
 *   UPDATE_SETTINGS patch { preferences }（确认记录⑧：reducer「写 preferences」语义）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DARK_NEUTRALS, LIGHT_NEUTRALS, DEFAULT_PREFERENCES, type ThemePreferences } from '@acme/core';

// 受控系统色容器（vi.hoisted 提升到 mock 工厂之前）
const scheme = vi.hoisted(() => ({ current: 'dark' as 'dark' | 'light' | null }));

// react-native 替身：useColorScheme 返回受控值；其余以最小桩兜底 import 链
vi.mock('react-native', () => ({
  useColorScheme: () => scheme.current,
  useWindowDimensions: () => ({ width: 390, height: 844, scale: 2, fontScale: 1 }),
  Appearance: { getColorScheme: () => scheme.current, addChangeListener: vi.fn() },
  StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
  Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  View: 'div',
  Text: 'span',
  Pressable: 'button',
  StatusBar: () => null,
}));

// async-storage 内存桩（theme → useStore → utils/storage import 链兜底，照 theme.test 范本）
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

import { ThemeProvider, useAppTheme, barStyleFor, type AppThemeValue } from '../src/theme';
import { useStore } from '../src/store/useStore';

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

/** 重渲染探针（模拟系统色事件后的 UI 即时刷新落点）。 */
async function rerenderProbe(): Promise<void> {
  await act(async () => {
    root!.render(createElement(ThemeProvider, null, createElement(Probe)));
  });
}

const setMode = (mode: ThemePreferences['mode']): void => {
  expect(useStore.getState().dispatch({
    type: 'UPDATE_SETTINGS',
    patch: { preferences: { ...DEFAULT_PREFERENCES, mode } },
  })).toBe(true);
};

beforeEach(() => {
  captured = undefined;
  scheme.current = 'dark';
  // 归位：手动 dark 起点（用例间互不污染）
  setMode('dark');
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

describe('REQ20261121200415 三态 system 跟随（AC-9）', () => {
  it('should_resolve_theme_from_device_scheme_when_mode_is_system', async () => {
    setMode('system');
    await renderProbe();

    // system × 系统暗色 → dark 派生（palette 引用 core 常量本体）
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
    expect(barStyleFor(captured!.theme)).toBe('light-content');
  });

  it('should_switch_theme_instantly_when_system_scheme_changes', async () => {
    setMode('system');
    await renderProbe();
    expect(captured!.theme).toBe('dark');

    // 系统切到 light：useColorScheme 变化 → ThemeProvider 重派生 → 即时切换
    scheme.current = 'light';
    await rerenderProbe();

    expect(captured!.theme).toBe('light');
    expect(captured!.palette).toBe(LIGHT_NEUTRALS);
    expect(barStyleFor(captured!.theme)).toBe('dark-content');

    // 切回 dark：即时回到暗色派生
    scheme.current = 'dark';
    await rerenderProbe();
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
  });
});

describe('REQ20261121200415 手动选择脱离 system（AC-9）', () => {
  it('should_ignore_system_scheme_changes_when_manual_theme_selected', async () => {
    await renderProbe();
    expect(captured!.theme).toBe('dark'); // 手动 dark 起点

    // 手动选 light：无论系统处于何色，即切 light
    scheme.current = 'dark';
    setMode('light');
    await act(async () => {
      await Promise.resolve();
    });
    expect(captured!.theme).toBe('light');
    expect(captured!.palette).toBe(LIGHT_NEUTRALS);

    // 脱离后系统色变化不再改变用户选择
    scheme.current = 'dark';
    await rerenderProbe();
    expect(captured!.theme).toBe('light');

    // 手动选 dark：系统为 light 时仍保持 dark
    scheme.current = 'light';
    setMode('dark');
    await act(async () => {
      await Promise.resolve();
    });
    expect(captured!.theme).toBe('dark');
    expect(captured!.palette).toBe(DARK_NEUTRALS);
    expect(barStyleFor(captured!.theme)).toBe('light-content');
  });
});
