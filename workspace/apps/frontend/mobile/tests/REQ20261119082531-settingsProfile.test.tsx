/**
 * @vitest-environment jsdom
 *
 * REQ-20261119-082531（个人中心）mobile 端契约用例（AC-8）：
 * - Settings 员工账号区：B 会话（员工账密登录）下资料/安全/会话/通知四卡渲染；
 *   无 B 会话时改密卡隐藏、仅展示引导文案（显隐语义）；
 * - 改密卡提交：PUT /admin/v1/auth/me/password（B 令牌显式头）；OLD_PASSWORD_INVALID 文案；
 * - 资料卡：PUT /me 提交 nickname；会话卡：DELETE /admin/v1/auth/me/sessions/others；
 * - 通知开关：PUT /admin/v1/system/me/preferences {messagePush}（prefs API 接线）；
 * - 推送令牌注册（登录后注册 + 降级）：EXPO_PUBLIC_PROJECT_ID 缺省 → 不触 expo-notifications、
 *   零网络外呼；有 projectId 且有 B 会话 → getDevicePushTokenAsync → POST push-tokens；
 * - i18n：profile.mobile 域键 zh/en 成对。
 * 装配照 mobile/tests/REQ20261119040838-qrLogin.test.tsx（RN 宿主标签替身 + expo 模块内存桩）。
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

// expo-notifications 桩：getDevicePushTokenAsync 由桩捕获（断言降级路径未被调用）
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

import { i18n } from '@acme/core';
import { ThemeProvider } from '../src/theme';
import { useBSessionStore } from '../src/store/bSessionStore';
import { registerPushTokenAfterLogin } from '../src/utils/push';
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

function qq(testId: string): HTMLElement | null {
  return container.querySelector(`[data-testid="${testId}"]`);
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
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
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

/** Settings 屏装配：fetch 桩（画像/偏好缺省视图）+ Theme/Toast Provider；缺省播种 B 会话（员工卡解锁）。 */
async function renderSettings(options: {
  me?: unknown;
  prefs?: unknown;
  seedSession?: boolean;
  routes?: Record<string, (r: { url: URL; method: string }) => Response | undefined>;
} = {}): Promise<ReturnType<typeof vi.fn>> {
  const { me = meView, prefs = { messagePush: false }, seedSession = true, routes = {} } = options;
  if (seedSession) {
    seedBSession();
  }
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me' && r.method === 'GET') return envelope(me);
    if (p === '/admin/v1/system/me/preferences' && r.method === 'GET') return envelope(prefs);
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

// ————— AC-8 员工账号区渲染与显隐 —————

describe('REQ20261119082531 Settings 员工账号区（AC-8）', () => {
  it('should_render_profile_password_session_notification_cards_when_bSessionPresent', async () => {
    await renderSettings();

    // 四卡标题齐全（资料精简版/安全/会话/通知）
    expect(container.textContent).toContain('个人资料');
    expect(container.textContent).toContain('账户安全');
    expect(container.textContent).toContain('在线会话');
    expect(container.textContent).toContain('通知偏好');
    // 改密三输入 + 提交/下线其他按钮就位
    expect(byLabel('当前密码')).toBeTruthy();
    expect(byLabel('新密码')).toBeTruthy();
    expect(byLabel('确认新密码')).toBeTruthy();
    expect(q('profile-password-submit')).toBeTruthy();
    expect(q('profile-kick-others')).toBeTruthy();
    expect(q('profile-nickname-save')).toBeTruthy();
    // 昵称回显（GET /me B 令牌画像）
    expect((byLabel('昵称') as HTMLInputElement).value).toBe('扫码员工');
    // 通知开关就位
    expect(byLabel('APP 消息推送')).toBeTruthy();
  });

  it('should_hidePasswordCardAndShowGuidance_when_noBSession', async () => {
    await renderSettings({ seedSession: false });

    // 无 B 会话（未员工登录）：改密卡隐藏，仅展示引导文案
    expect(byLabel('当前密码')).toBeNull();
    expect(qq('profile-password-submit')).toBeNull();
    expect(container.textContent).toContain('员工账密登录后，可在此维护个人资料');
  });

  it('should_putMeNicknameWithBearerToken_when_saveNicknameClicked', async () => {
    const fetchMock = await renderSettings();
    await type(byLabel('昵称')!, '新昵称');
    await click(q('profile-nickname-save'));

    const puts = callsTo(fetchMock, '/admin/v1/system/me', 'PUT');
    expect(puts).toHaveLength(1);
    expect(parseBody(puts[0]?.body)).toEqual({ nickname: '新昵称' });
    expect(new Headers(puts[0]?.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    expect(container.textContent).toContain('昵称已保存');
  });
});

// ————— AC-8 改密卡提交（B 令牌显式头 + 错误文案） —————

const fillPasswordForm = async () => {
  await type(byLabel('当前密码')!, 'old-123');
  await type(byLabel('新密码')!, 'new-123456');
  await type(byLabel('确认新密码')!, 'new-123456');
};

describe('REQ20261119082531 改密卡提交', () => {
  it('should_putSelfPasswordWithBearerToken_when_changePasswordSubmitted', async () => {
    const fetchMock = await renderSettings();
    await fillPasswordForm();
    await click(q('profile-password-submit'));

    const puts = callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT');
    expect(puts).toHaveLength(1);
    expect(parseBody(puts[0]?.body)).toEqual({ oldPassword: 'old-123', newPassword: 'new-123456' });
    expect(new Headers(puts[0]?.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    // 成功提示「其他设备已下线」（契约③：改密后其他会话全部 KICKED）
    expect(container.textContent).toContain('密码已修改，其他设备已下线');
  });

  it('should_showOldPasswordInvalidToast_when_serverRejectsOldPassword', async () => {
    const fetchMock = await renderSettings({
      routes: {
        '/auth/me/password': () => failEnvelope('OLD_PASSWORD_INVALID', '当前密码不正确', 200),
      },
    });
    await fillPasswordForm();
    await click(q('profile-password-submit'));

    expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(1);
    expect(container.textContent).toContain('当前密码不正确');
  });

  it('should_blockSubmitWithMismatchToast_when_confirmPasswordDiffers', async () => {
    const fetchMock = await renderSettings();
    await type(byLabel('当前密码')!, 'old-123');
    await type(byLabel('新密码')!, 'new-123456');
    await type(byLabel('确认新密码')!, 'other-123456');
    await click(q('profile-password-submit'));

    expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(0);
    expect(container.textContent).toContain('两次输入的新密码不一致');
  });
});

// ————— AC-8 会话卡（下线其他设备） —————

describe('REQ20261119082531 会话卡下线其他设备', () => {
  it('should_deleteSessionsOthersWithBearerToken_when_kickOthersClicked', async () => {
    const fetchMock = await renderSettings();
    await click(q('profile-kick-others'));

    const deletes = callsTo(fetchMock, '/admin/v1/auth/me/sessions/others', 'DELETE');
    expect(deletes).toHaveLength(1);
    expect(new Headers(deletes[0]?.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    expect(container.textContent).toContain('其他设备已下线');
  });
});

// ————— AC-8 通知开关接 prefs API —————

describe('REQ20261119082531 通知开关（prefs API）', () => {
  it('should_putMessagePushTrueWithBearerToken_when_notificationSwitchToggledOn', async () => {
    const fetchMock = await renderSettings();
    const sw = byLabel('APP 消息推送')!;
    await click(sw);

    const puts = callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT');
    expect(puts).toHaveLength(1);
    expect(parseBody(puts[0]?.body)).toEqual({ messagePush: true });
    expect(new Headers(puts[0]?.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    expect(container.textContent).toContain('通知偏好已保存');
    // 未配置 EAS projectId：开启偏好后推送注册降级跳过并告知（不触系统模块、零外呼）
    expect(push.getToken).not.toHaveBeenCalled();
    expect(callsTo(fetchMock, '/admin/v1/system/me/push-tokens', 'POST')).toHaveLength(0);
    expect(container.textContent).toContain('未配置 EAS projectId，推送注册已跳过');
  });

  it('should_rollbackSwitchState_when_preferencesPutRejected', async () => {
    const fetchMock = await renderSettings({
      routes: {
        '/system/me/preferences': ({ method }) => (method === 'PUT'
          ? failEnvelope('SYS-01-0001', '偏好参数非法', 200)
          : undefined),
      },
    });
    const sw = byLabel('APP 消息推送')!;
    await click(sw);

    expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(1);
    // 失败回滚：开关保持关闭态
    expect(container.textContent).not.toContain('通知偏好已保存');
  });
});

// ————— AC-8 推送令牌注册（登录后注册 + projectId 降级） —————

describe('REQ20261119082531 推送令牌注册', () => {
  it('should_registerPushTokenWithBearerToken_when_projectIdPresent', async () => {
    vi.stubEnv('EXPO_PUBLIC_PROJECT_ID', 'eas-proj-1');
    seedBSession();
    push.getToken.mockResolvedValue({ type: 'ios', data: 'expo-token-9' });
    const calls: Array<{ url: URL; init?: RequestInit }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: new URL(String(input), 'http://localhost'), init });
      return new Response(null, { status: 200 });
    }));

    const outcome = await registerPushTokenAfterLogin();

    expect(outcome).toBe('registered');
    expect(push.getToken).toHaveBeenCalledTimes(1);
    const posts = calls.filter((c) => c.url.pathname === '/admin/v1/system/me/push-tokens');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.init?.method).toBe('POST');
    expect(parseBody(posts[0]!.init?.body)).toEqual({ token: 'expo-token-9', platform: 'ios' });
    expect(new Headers(posts[0]!.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
  });

  it('should_skipPushRegistrationWithZeroCalls_when_noExpoPublicProjectId', async () => {
    // 不 stub EXPO_PUBLIC_PROJECT_ID（缺省降级路径）
    seedBSession();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const outcome = await registerPushTokenAfterLogin();

    expect(outcome).toBe('skipped-no-project');
    // 降级：不触 expo-notifications、零网络外呼
    expect(push.getToken).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('should_skipPushRegistration_when_projectIdPresentButNoBSession', async () => {
    vi.stubEnv('EXPO_PUBLIC_PROJECT_ID', 'eas-proj-1');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const outcome = await registerPushTokenAfterLogin();

    expect(outcome).toBe('skipped-no-session');
    expect(push.getToken).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// ————— i18n：profile.mobile 域键 zh/en 成对 —————

function leafPaths(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') {
    return [prefix];
  }
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    leafPaths(v, prefix ? `${prefix}.${k}` : k),
  );
}

describe('REQ20261119082531 i18n profile.mobile 域（成对）', () => {
  it('should_pair_zh_and_en_profileMobileShapes_when_employeeAccountKeysAdded', async () => {
    const data = (i18n as unknown as { store: { data: Record<string, { translation: { profile: { mobile: unknown } } }> } }).store.data;
    const zh = leafPaths(data['zh-CN']!.translation.profile.mobile).sort();
    const en = leafPaths(data['en-US']!.translation.profile.mobile).sort();
    expect(zh.length).toBeGreaterThan(0);
    expect(en).toEqual(zh);
    expect(i18n.t('profile.mobile.pushSkipNoProject')).toBe('未配置 EAS projectId，推送注册已跳过');
    expect(i18n.t('profile.mobile.accountTitle')).toBe('员工账号');
  });
});
