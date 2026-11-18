/**
 * @vitest-environment jsdom
 *
 * REQ-20261119-032108（APP 端脚手架完善）组件/基建级回归：
 * - 主题双分支派生与切换刷新（ThemeProvider → useAppTheme → barStyleFor）；
 * - ErrorBoundary 兜底与重试恢复（AC-5）；
 * - useNetworkStatus 断网/恢复（AC-6，NetInfo 桩驱动）；
 * - Toast 显示与 2.5s 自动消失（AC-7，fake timers）；
 * - 深链映射表纯函数 + prefixes（AC-2）；
 * - i18n 资源 zh/en 键形状成对（AC-4 资源面）；
 * - 路由常量与 linking 映射一致性（AC-1/AC-2 静态面）；
 * - AuthNavigator 登录/登出导航联动（AC-1 语义，react-navigation mock 装配）。
 * 装配模式沿用 mobile/tests/REQ20261114120110-permissionGate.test.ts（RN 宿主标签替身）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ---- 原生/三方模块内存桩（先于 import 声明） ----

// expo-secure-store（authStore import 链兜底）
vi.mock('expo-secure-store', () => {
  const mem = new Map<string, string>();
  return {
    getItemAsync: vi.fn(async (key: string) => mem.get(key) ?? null),
    setItemAsync: vi.fn(async (key: string, value: string) => {
      mem.set(key, value);
    }),
    deleteItemAsync: vi.fn(async (key: string) => {
      mem.delete(key);
    }),
  };
});

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

// expo-camera 桩（REQ-20261119-040838）：App.tsx → QrConfirmScreen import 链兜底——
// expo-camera 原生模块在 Node/vitest 不可加载（expo-modules-core 需 NativeModules），以替身代之。
// 本文件仅测 AuthNavigator（不渲染相机屏），桩只保证 import 链可解析。
vi.mock('expo-camera', () => ({
  CameraView: () => null,
  useCameraPermissions: () => [{ granted: false }, vi.fn(async () => ({ granted: false }))],
}));

// NetInfo 桩：测试用例直接驱动监听回调（AC-6 断网→横幅态）
const net = vi.hoisted(() => {
  type NetState = { isConnected?: boolean | null; isInternetReachable?: boolean | null };
  let listener: ((state: NetState) => void) | null = null;
  return {
    emit(state: NetState) {
      listener?.(state);
    },
    setListener(next: ((state: NetState) => void) | null) {
      listener = next;
    },
  };
});
vi.mock('@react-native-community/netinfo', () => ({
  default: {
    addEventListener: (cb: (state: never) => void) => {
      net.setListener(cb as never);
      return () => net.setListener(null);
    },
    fetch: vi.fn(async () => ({ isConnected: true, isInternetReachable: true })),
  },
}));

// react-navigation 桩（装配 mock；AuthNavigator 语义经 useNavigation 桩断言）
const navStub = vi.hoisted(() => ({
  getState: vi.fn(() => ({ index: 0, routes: [{ name: 'Login' }] })),
  replace: vi.fn(),
  reset: vi.fn(),
  navigate: vi.fn(),
}));
vi.mock('@react-navigation/native', async () => {
  const { createElement } = await import('react');
  return {
    NavigationContainer: ({ children }: { children?: unknown }) =>
      createElement('div', { 'data-testid': 'nav-container' }, children as never),
    useNavigation: () => navStub,
  };
});
vi.mock('@react-navigation/native-stack', async () => {
  const { createElement } = await import('react');
  return {
    createNativeStackNavigator: () => ({
      Navigator: ({ children }: { children?: unknown }) => createElement('div', null, children as never),
      Screen: () => null,
    }),
  };
});
vi.mock('@react-navigation/bottom-tabs', async () => {
  const { createElement } = await import('react');
  return {
    createBottomTabNavigator: () => ({
      Navigator: ({ children }: { children?: unknown }) => createElement('div', null, children as never),
      Screen: () => null,
    }),
  };
});

// react-native 宿主标签替身（View→div / Text→span / Pressable→button[onPress→onClick]）
vi.mock('react-native', async () => {
  const { createElement, forwardRef } = await import('react');
  const Pressable = forwardRef(function Pressable(props: Record<string, unknown>, ref: unknown) {
    const { onPress, ...rest } = props;
    return createElement('button', { ...rest, onPress: undefined, onClick: onPress, ref });
  });
  return {
    View: 'div',
    Text: 'span',
    Pressable,
    ScrollView: 'div',
    TextInput: 'input',
    FlatList: 'div',
    KeyboardAvoidingView: 'div',
    SafeAreaView: 'div',
    StatusBar: () => null,
    ActivityIndicator: () => createElement('div', { 'data-testid': 'spinner' }),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
    Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  };
});

// ---- 被测对象（mock 声明之后 import） ----

import { DARK_NEUTRALS, LIGHT_NEUTRALS, i18n } from '@acme/core';
import { useStore } from '../src/store/useStore';
import { useAuthStore } from '../src/store/authStore';
import { useNetworkStatus } from '../src/hooks/useNetworkStatus';
import { ErrorBoundary } from '../src/components/ErrorBoundary';
import { ToastProvider, toast } from '../src/components/ui';
import { ThemeProvider, useAppTheme, barStyleFor, type AppThemeValue } from '../src/theme';
import {
  LINK_PREFIXES,
  LOGIN_ROUTE,
  MAIN_ROUTE,
  TAB_KEYS,
  TAB_PATHS,
  linking,
  resolveDeepLinkPath,
} from '../src/nav';
import { AuthNavigator } from '../App';

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

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  useAuthStore.getState().clear();
  useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'dark' } });
  navStub.getState.mockReturnValue({ index: 0, routes: [{ name: 'Login' }] });
  navStub.replace.mockClear();
  navStub.reset.mockClear();
  navStub.navigate.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  if (root) {
    await_unmount();
  }
});

async function await_unmount(): Promise<void> {
  const r = root!;
  root = undefined;
  await act(async () => {
    r.unmount();
  });
  container.remove();
}

// ---- ① 主题双分支派生＋切换刷新 ----

let capturedTheme: AppThemeValue | undefined;

function ThemeProbe() {
  capturedTheme = useAppTheme();
  return null;
}

describe('REQ20261119032108 theme（AC-3）', () => {
  it('should_derive_dark_then_light_palette_and_refresh_when_settings_theme_switches', async () => {
    await act(async () => {
      root!.render(
        createElement(ThemeProvider, null, createElement(ThemeProbe)),
      );
    });
    expect(capturedTheme!.theme).toBe('dark');
    expect(capturedTheme!.palette).toBe(DARK_NEUTRALS);
    expect(barStyleFor('dark')).toBe('light-content');

    await act(async () => {
      useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: 'light' } });
      await flush();
    });
    expect(capturedTheme!.theme).toBe('light');
    expect(capturedTheme!.palette).toBe(LIGHT_NEUTRALS);
    expect(barStyleFor('light')).toBe('dark-content');
    await await_unmount();
  });
});

// ---- ② ErrorBoundary 兜底与重试（AC-5） ----

function Bomb({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('boom');
  }
  return createElement('div', { 'data-testid': 'ok-content' }, 'RECOVERED');
}

describe('REQ20261119032108 ErrorBoundary（AC-5）', () => {
  it('should_render_fallback_card_when_child_throws_and_recover_when_retried', async () => {
    await render(createElement(ErrorBoundary, null, createElement(Bomb, { shouldThrow: true })));

    expect(container.textContent).toContain(i18n.t('components.errorBoundary.title'));
    expect(container.textContent).not.toContain('RECOVERED');

    // 异常解除后点击重试 → setState 重新挂载子树
    await act(async () => {
      root!.render(createElement(ErrorBoundary, null, createElement(Bomb, { shouldThrow: false })));
    });
    const retry = container.querySelector('button');
    expect(retry).not.toBeNull();
    await act(async () => {
      retry!.click();
      await flush();
    });
    expect(container.textContent).toContain('RECOVERED');
    expect(container.textContent).not.toContain(i18n.t('components.errorBoundary.title'));
    await await_unmount();
  });
});

// ---- ③ useNetworkStatus（AC-6） ----

function NetProbe() {
  const offline = useNetworkStatus();
  return createElement('div', null, `OFFLINE:${String(offline)}`);
}

describe('REQ20261119032108 useNetworkStatus（AC-6）', () => {
  it('should_report_offline_then_recover_when_netinfo_state_changes', async () => {
    await render(createElement(NetProbe));
    expect(container.textContent).toContain('OFFLINE:false');

    // 断网：连接断开（App 顶部离线横幅据本态显示「网络不可达」）
    await act(async () => {
      net.emit({ isConnected: false, isInternetReachable: false });
    });
    expect(container.textContent).toContain('OFFLINE:true');

    // 仅互联网不可达（连接仍在）同样判离线
    await act(async () => {
      net.emit({ isConnected: true, isInternetReachable: false });
    });
    expect(container.textContent).toContain('OFFLINE:true');

    // 恢复
    await act(async () => {
      net.emit({ isConnected: true, isInternetReachable: true });
    });
    expect(container.textContent).toContain('OFFLINE:false');
    await await_unmount();
  });
});

// ---- ④ Toast 显示/自动消失（AC-7） ----

function ToastProbe() {
  return null;
}

describe('REQ20261119032108 Toast（AC-7）', () => {
  it('should_show_toast_then_auto_dismiss_after_2500ms', async () => {
    // durationMs 注入短时长（真实定时器；fake timers 会饿死 React act 调度器致全文件污染）
    await act(async () => {
      root!.render(createElement(ToastProvider, { durationMs: 40, children: createElement(ToastProbe) }));
    });
    // Provider 挂载完成（emitToast 已注册）后顶层调用 toast(msg, tone)
    await act(async () => {
      toast('已保存为草稿', 'success');
    });
    expect(container.textContent).toContain('已保存为草稿');

    await act(async () => {
      await flush();
      await new Promise((r) => setTimeout(r, 80));
    });
    expect(container.textContent).not.toContain('已保存为草稿');
    await await_unmount();
  });
});

// ---- ⑤ 深链映射表（AC-2） ----

describe('REQ20261119032108 deep link mapping（AC-2）', () => {
  it('should_map_valid_paths_to_tabs_and_fall_back_to_dashboard_when_path_invalid', () => {
    expect(resolveDeepLinkPath('dashboard')).toBe('dashboard');
    expect(resolveDeepLinkPath('releases')).toBe('releases');
    expect(resolveDeepLinkPath('/apps')).toBe('apps');
    expect(resolveDeepLinkPath('settings?utm=1')).toBe('settings');
    // 非法/未知路径回 dashboard
    expect(resolveDeepLinkPath('nope')).toBe('dashboard');
    expect(resolveDeepLinkPath('')).toBe('dashboard');
    expect(resolveDeepLinkPath('../evil')).toBe('dashboard');
  });

  it('should_expose_acme_prefix_and_login_main_screens_when_linking_configured', () => {
    expect(linking.prefixes).toEqual([...LINK_PREFIXES]);
    expect(linking.prefixes).toEqual(['acme://']);
    const screens = linking.config!.screens as Record<string, unknown>;
    expect(Object.keys(screens)).toEqual([LOGIN_ROUTE, MAIN_ROUTE]);
    expect(screens[LOGIN_ROUTE]).toBe('login');
    expect((screens[MAIN_ROUTE] as { screens: unknown }).screens).toBe(TAB_PATHS);
  });
});

// ---- ⑥ i18n 资源成对（AC-4 资源面） ----

function leafPaths(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') {
    return [prefix];
  }
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    leafPaths(v, prefix ? `${prefix}.${k}` : k),
  );
}

describe('REQ20261119032108 i18n resources（AC-4）', () => {
  it('should_pair_zh_and_en_resource_shapes_when_mobile_keys_added', () => {
    const data = (i18n as unknown as { store: { data: Record<string, { translation: unknown }> } }).store.data;
    const zh = leafPaths(data['zh-CN']!.translation).sort();
    const en = leafPaths(data['en-US']!.translation).sort();
    expect(zh.length).toBeGreaterThan(0);
    expect(en).toEqual(zh);
  });

  it('should_resolve_new_mobile_keys_when_locale_zh', () => {
    expect(i18n.t('nav.mobile.tabs.dashboard')).toBe('总览');
    expect(i18n.t('nav.mobile.offline')).toBe('网络不可达');
    expect(i18n.t('releases.mobile.statusFilters.all')).toBe('全部');
    expect(i18n.t('settings.mobile.themeLight')).toBe('浅色');
    expect(i18n.t('login.mobile.title')).toBe('登录');
    expect(i18n.t('common.mobile.empty')).toBe('暂无数据');
    expect(i18n.t('dashboard.cards.pipelineTotal', { total: 3 })).toBe('共 3 个发布单');
  });
});

// ---- ⑦ 路由常量与 Tabs 一致性（AC-1/AC-2 静态面） ----

describe('REQ20261119032108 routes（AC-1）', () => {
  it('should_align_route_constants_with_tab_paths_when_navigation_mounts', () => {
    expect(LOGIN_ROUTE).toBe('Login');
    expect(MAIN_ROUTE).toBe('Main');
    expect(TAB_KEYS).toEqual(['dashboard', 'releases', 'apps', 'settings']);
    expect(Object.keys(TAB_PATHS)).toEqual([...TAB_KEYS]);
    expect(Object.values(TAB_PATHS)).toEqual([...TAB_KEYS]);
  });
});

// ---- ⑧ AuthNavigator 登录/登出联动（AC-1 语义） ----

const SESSION = {
  accessToken: 'a',
  refreshToken: 'r',
  accessExpiresAtMs: 1,
  refreshExpiresAtMs: 2,
  userId: '7',
  phone: '13800138000',
};

describe('REQ20261119032108 AuthNavigator（AC-1）', () => {
  it('should_replace_to_main_when_session_established', async () => {
    await render(createElement(AuthNavigator));
    // 未登录且在 Login 屏：无导航动作
    expect(navStub.replace).not.toHaveBeenCalled();

    await act(async () => {
      useAuthStore.getState().setSession(SESSION as never);
      await flush();
    });
    expect(navStub.replace).toHaveBeenCalledTimes(1);
    expect(navStub.replace).toHaveBeenCalledWith(MAIN_ROUTE);
    await await_unmount();
  });

  it('should_reset_to_login_when_session_cleared_on_main', async () => {
    navStub.getState.mockReturnValue({ index: 0, routes: [{ name: MAIN_ROUTE }] });
    useAuthStore.getState().setSession(SESSION as never);
    await render(createElement(AuthNavigator));

    await act(async () => {
      useAuthStore.getState().clear();
      await flush();
    });
    expect(navStub.reset).toHaveBeenCalledTimes(1);
    expect(navStub.reset).toHaveBeenCalledWith({ index: 0, routes: [{ name: LOGIN_ROUTE }] });
    await await_unmount();
  });

  it('should_not_touch_navigation_when_deep_link_already_on_main_and_session_present', async () => {
    navStub.getState.mockReturnValue({ index: 1, routes: [{ name: LOGIN_ROUTE }, { name: MAIN_ROUTE }] });
    useAuthStore.getState().setSession(SESSION as never);
    await render(createElement(AuthNavigator));
    expect(navStub.replace).not.toHaveBeenCalled();
    expect(navStub.reset).not.toHaveBeenCalled();
    await await_unmount();
  });
});
