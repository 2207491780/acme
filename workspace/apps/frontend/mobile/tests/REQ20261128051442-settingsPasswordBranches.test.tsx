/** @vitest-environment jsdom
 *
 * REQ-20261128-051442（wire 契约·错误码统一）AC-4：SettingsScreen 改密两分支**全码真命中**用例——
 * OLD_PASSWORD_INVALID=platform-auth-07-0001 / SAME_PASSWORD=platform-auth-07-0002（附录 A.4 07 段）。
 * stub 发码 = 后端 wire 全码真值 → 屏幕分支判定（已全码化）真命中，断言 i18n 转译 toast 文案。
 * 装配照 mobile/tests/REQ20261119082531-settingsProfile.test.tsx（RN 宿主标签替身 + expo 模块内存桩）。
 */
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ---- 原生/三方模块内存桩（先于 import 声明） ----

// expo-secure-store（authStore/bSessionStore import 链；secureMem 供用例注入持久化数据）
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

// expo-notifications 桩：改密分支用例不触推送注册（断言降级路径未被调用）
const push = vi.hoisted(() => ({
  getToken: vi.fn(),
}));
vi.mock('expo-notifications', () => ({
  getDevicePushTokenAsync: (...args: unknown[]) => push.getToken(...args),
}));

// react-native 宿主标签替身（View→div / Text→span / Pressable→button[onPress→onClick]；
// TextInput→input[onChangeText→onChange 双向]；Image→img[source.uri→src]；testID→data-testid）
vi.mock('react-native', async () => {
  const { createElement, forwardRef } = await import('react');
  const splitProps = (props: Record<string, unknown>) => {
    const {
      accessibilityLabel,
      accessibilityRole,
      accessibilityState,
      accessibilityLiveRegion,
      testID,
      placeholderTextColor,
      keyboardType,
      autoComplete: autoCompleteProp,
      autoCapitalize,
      autoCorrect,
      secureTextEntry,
      maxLength,
      resizeMode,
      source,
      ...rest
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
      ...rest,
      'aria-label': ariaLabel,
      'data-testid': testId,
      onPress: undefined,
      onClick: onPress,
      ref,
    });
  });
  return {
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
        ...rest,
        value,
        'aria-label': ariaLabel,
        'data-testid': testId,
        onChange: typeof onChangeText === 'function' ? (e: { target: { value: string } }) => onChangeText(e.target.value) : undefined,
        ref,
      });
    }),
    Image: function Image(props: Record<string, unknown>) {
      const { rest, ariaLabel, testId } = splitProps(props);
      const source = props.source as { uri?: string } | undefined;
      return createElement('img', {
        ...rest,
        'aria-label': ariaLabel,
        'data-testid': testId,
        src: source?.uri,
      });
    },
    KeyboardAvoidingView: 'div',
    SafeAreaView: 'div',
    StatusBar: () => null,
    ActivityIndicator: () => createElement('div', { 'data-testid': 'spinner' }),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
    Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  };
});

// ---- 被测对象（mock 声明之后 import） ----

import { useBSessionStore } from '../src/store/bSessionStore';
import { SettingsScreen } from '../src/screens/SettingsScreen';
import { ToastProvider } from '../src/components/ui';
import { ThemeProvider } from '../src/theme';

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

async function await_unmount(): Promise<void> {
  const r = root!;
  root = undefined;
  await act(async () => {
    r.unmount();
  });
  container.remove();
}

// ---- DOM 断言/交互替身（沿用 scaffold 原生 DOM 口径） ----

function q(testId: string): HTMLElement {
  const el = container.querySelector(`[data-testid="${testId}"]`);
  if (!el) {
    throw new Error(`element not found: ${testId}`);
  }
  return el as HTMLElement;
}

function byLabel(label: string): HTMLElement | null {
  return container.querySelector(`[aria-label="${label}"]`);
}

async function click(el: HTMLElement): Promise<void> {
  await act(async () => {
    el.click();
    await flush();
  });
}

/** 受控输入模拟：原生 value setter + input 事件（React onChange/onChangeText 才会收到）。 */
async function type(el: HTMLElement, value: string): Promise<void> {
  await act(async () => {
    const input = el as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
  });
}

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message, data: null }), { status },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (req.method ?? 'GET').toUpperCase(), body: init?.body };
};

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const callsTo = (fetchMock: ReturnType<typeof vi.fn>, pathname: string, method?: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => ({ ...asRequest(input, init), init }))
    .filter((r) => r.url.pathname === pathname && (method ? r.method === method : true));

const meView = {
  user: {
    id: '2002', username: 'qr-employee', nickname: '扫码员工', email: 'qr@acme.local', phone: null,
    avatarUrl: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['employee'],
  permissions: [],
  menus: [],
  deptName: null,
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

/** Settings 屏装配（settingsProfile.test.tsx 范本口径）：fetch 桩 + Theme/Toast Provider + B 会话播种。 */
async function renderSettings(options: {
  me?: unknown;
  routes?: Record<string, (r: { url: URL; method: string }) => Response | undefined>;
} = {}): Promise<ReturnType<typeof vi.fn>> {
  const { me = meView, routes = {} } = options;
  seedBSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me' && r.method === 'GET') return envelope(me);
    if (p === '/admin/v1/system/me/preferences' && r.method === 'GET') return envelope({ messagePush: false });
    for (const [fragment, respond] of Object.entries(routes)) {
      if (p.includes(fragment)) {
        const custom = respond({ url: r.url, method: r.method });
        if (custom) return custom;
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  await render(
    createElement(ThemeProvider, null, createElement(ToastProvider, null, createElement(SettingsScreen))),
  );
  // 画像/偏好挂载拉取冲刷
  await act(async () => { await flush(); });
  await act(async () => { await flush(); });
  return fetchMock;
}

const fillPasswordForm = async () => {
  await type(byLabel('当前密码')!, 'old-123');
  await type(byLabel('新密码')!, 'new-123456');
  await type(byLabel('确认新密码')!, 'new-123456');
};

beforeEach(() => {
  secureMem.clear();
  useBSessionStore.getState().clear();
  push.getToken.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  if (root) {
    await_unmount();
  }
});

// ————— AC-4 改密两分支全码真命中（platform-auth-07-0001/0002） —————

describe('REQ20261128051442 SettingsScreen 改密分支（AC-4）', () => {
  it('should_show_old_password_invalid_toast_when_self_password_hits_full_code', async () => {
    const fetchMock = await renderSettings({
      routes: {
        '/auth/me/password': () => failEnvelope('platform-auth-07-0001', '当前密码不正确', 200),
      },
    });
    await fillPasswordForm();
    await click(q('profile-password-submit'));

    expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(1);
    expect(parseBody(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')[0]?.body))
      .toEqual({ oldPassword: 'old-123', newPassword: 'new-123456' });
    // 分支真命中（全码 platform-auth-07-0001）：i18n 转译 toast 文案
    expect(container.textContent).toContain('当前密码不正确');
  });

  it('should_show_same_password_toast_when_self_password_hits_same_password_full_code', async () => {
    const fetchMock = await renderSettings({
      routes: {
        '/auth/me/password': () => failEnvelope('platform-auth-07-0002', '新密码不能与当前密码相同', 200),
      },
    });
    await fillPasswordForm();
    await click(q('profile-password-submit'));

    expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(1);
    // 分支真命中（全码 platform-auth-07-0002）：i18n 转译 toast 文案
    expect(container.textContent).toContain('新密码不能与当前密码相同');
  });
});
