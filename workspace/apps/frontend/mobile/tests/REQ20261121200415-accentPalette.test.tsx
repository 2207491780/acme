/**
 * @vitest-environment jsdom
 *
 * REQ-20261121-200415（THEME-3）AC-10：mobile 色板对齐契约用例。
 *
 * 表面 = 契约确认记录⑦ + AC-10：
 *   色板对齐 core ACCENTS 12 预设全呈现；customAccent 同步生效并在色板以选中态呈现；
 *   一期不做取色器 UI（负向断言）。预设点击写 preferences（确认记录⑧：脱离投影 patch）。
 *
 * 装配照 mobile/tests/REQ20261119082531-settingsProfile.test.tsx 范本：
 *   RN 宿主标签替身（View→div / Pressable→button[onPress→onClick]）+ expo 模块内存桩
 *   + fetch 桩信封 + ThemeProvider/ToastProvider 包裹 SettingsScreen。
 * 选择器口径（a11y 节点优先，pitfalls #7~#11）：
 *   12 预设按钮 aria-label="强调色 <key>"；customAccent 芯片 aria-label="settings.appearance.accentCustom"；
 *   选中态 = 芯片/按钮内存在勾选标记子元素（渲染树实测，不硬凑 role）。
 * store 初始化首个 dispatch 可能被水合拒绝 → 统一经 setPrefs（vi.waitFor 重试）写入偏好。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCENT_ORDER, DEFAULT_PREFERENCES, type ThemePreferences } from '@acme/core';

// ---- 原生/三方模块内存桩（先于 import 声明，照 settingsProfile 范本） ----

// expo-secure-store（authStore/bSessionStore import 链）
const secureMem = vi.hoisted(() => new Map<string, string>());
vi.mock('expo-secure-store', () => ({
  getItemAsync: vi.fn(async (key: string) => secureMem.get(key) ?? null),
  setItemAsync: vi.fn(async (key: string, value: string) => {
    secureMem.set(key, value);
  }),
  deleteItemAsync: vi.fn(async (key: string) => {
    secureMem.delete(key);
  }),
}));

// AsyncStorage 内存桩（theme/useStore import 链兜底）
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

// expo-notifications 桩（SettingsScreen → utils/push import 链；本套件不触推送）
vi.mock('expo-notifications', () => ({
  getDevicePushTokenAsync: vi.fn(),
}));

// react-native 宿主标签替身（settingsProfile 范本口径精简）
vi.mock('react-native', async () => {
  const { createElement, forwardRef } = await import('react');
  const splitProps = (props: Record<string, unknown>) => {
    const {
      accessibilityLabel, accessibilityRole, accessibilityState, accessibilityLiveRegion,
      testID, placeholderTextColor, keyboardType, autoComplete: autoCompleteProp, autoCapitalize,
      autoCorrect, secureTextEntry, maxLength, resizeMode, source, ...rest
    } = props;
    return {
      rest,
      ariaLabel: typeof accessibilityLabel === 'string' ? accessibilityLabel : undefined,
      testId: typeof testID === 'string' ? testID : undefined,
    };
  };
  const Pressable = forwardRef(function Pressable(props: Record<string, unknown>, ref: unknown) {
    const { onPress, ...restProps } = props;
    const { rest, ariaLabel, testId } = splitProps(restProps as Record<string, unknown>);
    return createElement('button', {
      ...rest, 'aria-label': ariaLabel, 'data-testid': testId, onPress: undefined, onClick: onPress, ref,
    });
  });
  return {
    useColorScheme: () => 'dark',
    useWindowDimensions: () => ({ width: 390, height: 844, scale: 2, fontScale: 1 }),
    View: forwardRef(function View(props: Record<string, unknown>, ref: unknown) {
      const { rest, ariaLabel, testId } = splitProps(props);
      return createElement('div', { ...rest, 'aria-label': ariaLabel, 'data-testid': testId, ref });
    }),
    Text: 'span',
    Pressable,
    ScrollView: 'div',
    TextInput: forwardRef(function TextInput(props: Record<string, unknown>, ref: unknown) {
      const { onChangeText, value, ...restProps } = props;
      const { rest, ariaLabel, testId } = splitProps(restProps as Record<string, unknown>);
      return createElement('input', {
        ...rest, value, 'aria-label': ariaLabel, 'data-testid': testId,
        onChange: typeof onChangeText === 'function' ? (e: { target: { value: string } }) => onChangeText(e.target.value) : undefined,
        ref,
      });
    }),
    Image: 'img',
    KeyboardAvoidingView: 'div',
    SafeAreaView: 'div',
    StatusBar: () => null,
    ActivityIndicator: () => createElement('div', { 'data-testid': 'spinner' }),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
    Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  };
});

// ---- 被测对象（mock 声明之后 import） ----

import { ThemeProvider } from '../src/theme';
import { useStore } from '../src/store/useStore';
import { useBSessionStore } from '../src/store/bSessionStore';
import { SettingsScreen } from '../src/screens/SettingsScreen';
import { ToastProvider } from '../src/components/ui';

// React 18 act 环境
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

let container: HTMLElement;
let root: Root | undefined;

async function render(node: React.ReactNode): Promise<void> {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root!.render(node);
  });
}

async function unmount(): Promise<void> {
  const r = root!;
  root = undefined;
  await act(async () => {
    r.unmount();
  });
  container.remove();
}

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const meView = {
  user: {
    id: '2002', username: 'qr-employee', nickname: '扫码员工', email: null, phone: null,
    status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['employee'],
  permissions: [],
  menus: [],
};

const seedBSession = () => {
  useBSessionStore.getState().setSession({
    accessToken: 'b-token-1',
    accessExpiresAtMs: Date.now() + 30 * 60_000,
    userId: '2002',
    username: 'qr-employee',
    nickname: '扫码员工',
    tenantCode: 'default',
  });
};

/** 偏好写入辅助：store 初始化首个 dispatch 可能未落定，重试至设置段落定（水合竞态兜底）。 */
const setPrefs = async (prefs: ThemePreferences): Promise<void> => {
  await vi.waitFor(() => {
    useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: prefs } });
    const settings = useStore.getState().state.settings as { preferences?: ThemePreferences };
    expect(settings.preferences).toEqual(prefs);
  });
};

const preferencesOf = (): ThemePreferences =>
  (useStore.getState().state.settings as { preferences: ThemePreferences }).preferences;

/** Settings 屏装配（fetch 桩画像/偏好缺省视图 + Theme/Toast Provider + B 会话）。 */
async function renderSettings(): Promise<void> {
  seedBSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const p = new URL(String(input), 'http://localhost').pathname;
    if (p === '/admin/v1/system/me') return envelope(meView);
    if (p === '/admin/v1/system/me/preferences') return envelope({ messagePush: false, themePreference: null });
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  await render(
    createElement(ThemeProvider, null, createElement(ToastProvider, null, createElement(SettingsScreen))),
  );
  await act(async () => { await flush(); });
  await act(async () => { await flush(); });
}

/** 按 aria-label 取色块（a11y 树优先；不硬凑 role，pitfalls #9）。 */
const swatchOf = (label: string): HTMLElement | null =>
  container.querySelector<HTMLElement>(`[aria-label="${label}"]`);

beforeEach(async () => {
  secureMem.clear();
  useBSessionStore.getState().clear();
  await setPrefs({ ...DEFAULT_PREFERENCES });
});

afterEach(async () => {
  vi.unstubAllGlobals();
  if (root) {
    await unmount();
  }
});

describe('REQ20261121200415 12 预设色板（AC-10）', () => {
  it('should_render_all_twelve_accent_presets_when_theme_palette_shown', async () => {
    await renderSettings();

    // 锚：core 单源 12 预设（确认记录⑦「色板对齐 ACCENTS 12 预设」）
    expect(ACCENT_ORDER).toHaveLength(12);
    // 全呈现：每个预设色块的 a11y 节点均存在
    for (const key of ACCENT_ORDER) {
      expect(swatchOf(`强调色 ${key}`), `预设 ${key} 应呈现于色板`).toBeTruthy();
    }
    const swatches = container.querySelectorAll('[aria-label^="强调色 "]');
    expect(swatches).toHaveLength(12);
  });

  it('should_write_preferences_accent_when_preset_swatch_selected', async () => {
    await renderSettings();
    expect(preferencesOf().accent).not.toBe('bronze');

    // 点击 bronze 色块 → 写 preferences.accent（脱离投影 patch，确认记录⑧）
    const swatch = swatchOf('强调色 bronze') as HTMLButtonElement;
    await act(async () => {
      swatch.click();
      await flush();
    });

    await vi.waitFor(() => expect(preferencesOf().accent).toBe('bronze'));
  });
});

describe('REQ20261121200415 customAccent 呈现（AC-10）', () => {
  it('should_present_custom_accent_with_selected_state_when_custom_accent_synced', async () => {
    // customAccent 经后端同步生效：一期无取色器写入 UI，色板以选中态呈现该色
    await setPrefs({ ...DEFAULT_PREFERENCES, customAccent: '#b08968' });
    await renderSettings();

    // 自定义色芯片（a11y 标记）呈现且携带选中标记子元素
    const custom = swatchOf('settings.appearance.accentCustom');
    expect(custom, 'customAccent 芯片应呈现于色板').toBeTruthy();
    expect(custom!.children.length, 'customAccent 芯片应带选中态标记').toBeGreaterThan(0);
  });
});

describe('REQ20261121200415 无取色器 UI（AC-10 负向）', () => {
  it('should_not_render_color_picker_when_accent_palette_rendered', async () => {
    await renderSettings();

    // 一期不做取色器：无取色入口文案、无 input[type=color]、无取色语义标记
    expect(container.querySelector('input[type="color"]')).toBeNull();
    expect(container.textContent).not.toContain('取色');
    const pickerMarked = Array.from(container.querySelectorAll<HTMLElement>('*')).filter((el) => {
      const label = `${el.getAttribute('aria-label') ?? ''} ${el.getAttribute('data-testid') ?? ''}`.toLowerCase();
      return /color[-_]?picker|取色/.test(label);
    });
    expect(pickerMarked).toHaveLength(0);
  });
});
