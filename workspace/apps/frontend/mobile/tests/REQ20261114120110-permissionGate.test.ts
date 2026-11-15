/**
 * @vitest-environment jsdom
 *
 * REQ-20261114-120110 AC-1：screen 级 PermissionGate 组件级断言（TL 裁决 AMB-1 不降级）。
 *
 * 组件公共 API（TL 钉死）：`{ permission: string; children: ReactNode; onBack?: () => void }`；
 * 权限源=mobile meStore.permissions；fail-closed 三态与 web 范本（web/src/App.tsx:68）对齐：
 *   - me 未加载 + error → 错误态 + 重试入口（重试=useMeStore.getState().load()）；
 *   - me 未加载（loading）→ loading 占位（无返回入口）；
 *   - me 已加载且缺权限码 → 403 提示 + 「返回」按钮（触发 onBack）；
 *   - 有权限 → 渲染 children。
 * 装配（TL 裁决 AMB-3：由 frontend 落装配）：mobile devDeps 需 jsdom + react-dom；
 * react-native 以宿主标签替身 mock（View→div / Text→span / Pressable→button[onPress→onClick]），
 * 对齐 core「零第三方渲染库」惯例（极简 renderHook/render 替身 + act/flush）。
 * 若 PermissionGate/ui/icons 链路用到其他 RN 导出（如 TouchableOpacity/ScrollView），
 * 装配时在本 mock 表按需补条目即可，用例断言面不变。
 */
import { createElement, type ReactNode } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 原生模块在 Node/vitest 下不可解析与加载：内存桩替代（范本：mobile/tests/auth.test.ts）
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

// AsyncStorage 以内存桩替代（meStore/use-dict import 链兜底，本文件不断言存储）
const asyncMem = vi.hoisted(() => {
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
  return { store, api };
});
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: asyncMem.api,
  AsyncStorage: asyncMem.api,
}));

// react-native 宿主标签替身：RN 组件树在 jsdom 下经 react-dom 渲染为零 DOM 开销桩
vi.mock('react-native', async () => {
  const { createElement, forwardRef } = await import('react');
  const Pressable = forwardRef(function Pressable(props: Record<string, unknown>, ref: unknown) {
    const { onPress, ...rest } = props;
    // RN onPress → DOM onClick（宿主替身唯一需要映射的交互面）
    return createElement('button', { ...rest, onPress: undefined, onClick: onPress, ref });
  });
  return {
    View: 'div',
    Text: 'span',
    Pressable,
    ActivityIndicator: () => createElement('div', { 'data-testid': 'spinner' }),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
    Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  };
});

import { PermissionGate } from '../src/components/PermissionGate';
import { useMeStore } from '../src/store/meStore';

// React 18 act 环境（在 RTL 之外直接使用 act 时必须显式声明）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

const envelope = (data: unknown) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status: 200 },
);

const meView = (permissions: string[]) => ({
  user: {
    id: '7',
    username: 'qa-admin',
    nickname: 'QA 管理员',
    email: null,
    phone: '13800138000',
    status: 'ENABLED',
    builtIn: false,
    roleIds: ['1'],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: '0',
  },
  roles: ['admin'],
  permissions,
  menus: [],
});

interface Rendered {
  text: () => string;
  buttons: () => HTMLButtonElement[];
  unmount: () => void;
}

/** 极简渲染替身（零第三方渲染库）：createRoot + act，children 用宿主元素承载可断言文本。 */
async function renderGate(permission: string, options: { permissions: string[]; onBack?: () => void } = { permissions: [] }): Promise<Rendered> {
  const children: ReactNode = createElement('div', { 'data-testid': 'screen-content' }, 'SCREEN_CONTENT');
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  await act(async () => {
    root.render(createElement(
      PermissionGate,
      { permission, onBack: options.onBack },
      children,
    ));
  });
  return {
    text: () => container.textContent ?? '',
    buttons: () => Array.from(container.querySelectorAll('button')),
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/** 注入 meStore 三态之一：已加载（给定权限集）。 */
function setMeLoaded(permissions: string[]): void {
  useMeStore.setState({ me: meView(permissions) as never, loading: false, loaded: true, error: null });
}

const GATED_PERMISSION = 'system:user:list';

beforeEach(() => {
  vi.unstubAllGlobals();
  useMeStore.getState().clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('REQ20261114120110 PermissionGate 组件（AC-1，fail-closed 三态）', () => {
  it('should_render_children_when_permission_granted', async () => {
    setMeLoaded([GATED_PERMISSION, 'system:dict:list']);

    const view = await renderGate(GATED_PERMISSION);

    expect(view.text()).toContain('SCREEN_CONTENT');
    view.unmount();
  });

  it('should_render_403_denial_with_back_entry_when_permission_missing', async () => {
    setMeLoaded(['system:dict:list']);
    const onBack = vi.fn();

    const view = await renderGate(GATED_PERMISSION, { permissions: ['system:dict:list'], onBack });

    // fail-closed：无权限不渲染 screen 内容
    expect(view.text()).not.toContain('SCREEN_CONTENT');
    // 403 提示 + 「返回」入口：该态唯一可点按钮即返回入口（文案口径=403 提示 + 「返回」按钮）
    const buttons = view.buttons();
    expect(buttons.length).toBeGreaterThan(0);
    buttons[0]!.click();
    expect(onBack).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it('should_fail_closed_when_permissions_empty', async () => {
    // 边界：me 已加载但权限集为空 → 任何权限码请求一律 403（fail-closed）
    setMeLoaded([]);
    const onBack = vi.fn();

    const view = await renderGate(GATED_PERMISSION, { permissions: [], onBack });

    expect(view.text()).not.toContain('SCREEN_CONTENT');
    expect(view.buttons().length).toBeGreaterThan(0);
    view.unmount();
  });

  it('should_render_loading_placeholder_without_back_entry_when_me_not_loaded', async () => {
    // me 未加载（loading）：占位，不渲染 children 也不给返回入口（fail-closed，防闪烁）
    useMeStore.setState({ me: null, loading: true, loaded: false, error: null });

    const view = await renderGate(GATED_PERMISSION);

    expect(view.text()).not.toContain('SCREEN_CONTENT');
    expect(view.buttons()).toHaveLength(0);
    view.unmount();
  });

  it('should_render_error_with_retry_entry_when_me_load_failed', async () => {
    // me 未加载 + error：错误态 + 重试入口（此前失败后权限页不得永远停留在加载中）
    useMeStore.setState({ me: null, loading: false, loaded: false, error: '当前用户信息加载失败' });
    const fetchMock = vi.fn(async () => envelope(meView([GATED_PERMISSION])));
    vi.stubGlobal('fetch', fetchMock);

    const view = await renderGate(GATED_PERMISSION);

    // 错误态：不渲染 children，提供重试入口
    expect(view.text()).not.toContain('SCREEN_CONTENT');
    const buttons = view.buttons();
    expect(buttons.length).toBeGreaterThan(0);

    // 重试 → load()（GET /admin/v1/system/me）→ 成功且有权限 → 渲染 children
    await act(async () => {
      buttons[0]!.click();
      await flush();
    });
    const meCalls = (fetchMock.mock.calls as unknown as Array<[RequestInfo | URL]>)
      .map(([input]) => String(typeof input === 'string' ? input : (input as Request).url))
      .filter((url) => url.includes('/admin/v1/system/me'));
    expect(meCalls.length).toBeGreaterThan(0);
    expect(view.text()).toContain('SCREEN_CONTENT');
    view.unmount();
  });
});
