/**
 * @vitest-environment jsdom
 *
 * REQ-20261119-040838（APP 扫码登录）组件/契约级用例：
 * - AC-7 员工账密登录：提交体含 captchaId/captchaCode（策略开）；B 会话落 acme.auth.bsession.v1
 *   且与 C 端会话（acme.auth.session.v1 / useAuthStore）互不干扰；
 * - B 会话校验：access-only（无 refreshToken 也合法）；本地过期/损坏即丢弃；
 * - AC-8 扫码确认：相机扫码解析 qrPayload JSON{t} → qrInfo（B 令牌 Authorization 头）→
 *   确认成功 toast + 返回；取消路径；相机权限被拒 → 手动输入备选入口可用；
 * - AC-9 api 层路径/载荷（B 令牌显式头）+ i18n 资源 zh/en 键形状成对。
 * 装配模式沿用 mobile/tests/REQ20261119032108-scaffold.test.tsx（RN 宿主标签替身），
 * expo-camera 桩可注入权限态并由用例直接驱动扫码回调。
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

// expo-camera 桩：用例可注入权限态；scanHandler 由用例直接驱动（模拟相机扫码回调）
const cam = vi.hoisted(() => ({
  scanHandler: null as ((e: { data: string }) => void) | null,
  permission: { granted: true } as { granted: boolean } | null,
  permissionRequests: 0,
}));
vi.mock('expo-camera', () => ({
  CameraView: (props: { onBarcodeScanned?: (e: { data: string }) => void }) => {
    cam.scanHandler = props.onBarcodeScanned ?? null;
    return createElement('div', { 'data-testid': 'camera-preview' });
  },
  useCameraPermissions: () => [
    cam.permission,
    () => {
      cam.permissionRequests += 1;
      cam.permission = { granted: true };
      return Promise.resolve({ granted: true });
    },
  ],
}));

// react-navigation 桩（navigate/goBack 由桩捕获断言）
const navStub = vi.hoisted(() => ({
  navigate: vi.fn(),
  goBack: vi.fn(),
}));
vi.mock('@react-navigation/native', () => ({
  useNavigation: () => navStub,
}));

// react-native 宿主标签替身（View→div / Text→span / Pressable→button[onPress→onClick]；
// TextInput→input[onChangeText→onChange 双向]；Image→img[source.uri→src]；testID→data-testid；
// RN 专有 props（a11y/键盘/样式伴生）不落 DOM，避免 react-dom 未知属性告警）
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
import { useAuthStore } from '../src/store/authStore';
import { useBSessionStore, bAuthHeaders } from '../src/store/bSessionStore';
import { authApi } from '../src/api/auth';
import { QR_CONFIRM_ROUTE } from '../src/nav';
import { LoginScreen } from '../src/screens/LoginScreen';
import { QrConfirmScreen } from '../src/screens/QrConfirmScreen';
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

// ---- DOM 断言/交互替身（@testing-library 不在 mobile 依赖清单，沿用 scaffold 原生 DOM 口径） ----

function qq(testId: string): HTMLElement | null {
  return container.querySelector(`[data-testid="${testId}"]`);
}

function q(testId: string): HTMLElement {
  const el = qq(testId);
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

const issued = () => ({
  accessToken: 'b-access-1',
  tokenType: 'Bearer',
  expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  userId: '2002',
  username: 'qr-employee',
  nickname: '扫码员工',
});

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

/** 员工 Tab 打开并填写表单（策略开路径：图形验证码就绪后填写）。 */
async function openEmployeeTabAndFill(): Promise<void> {
  await click(q('login-mode-employee'));
  // 策略查询 + 图形验证码加载冲刷
  await act(async () => { await flush(); });
  await act(async () => { await flush(); });
  // 策略开 → 图形验证码 data URI 渲染（RN Image 宿主替身 img）
  const img = q('admin-captcha-image');
  expect(img.getAttribute('src')).toContain('data:image/png;base64,');

  await type(q('tenant-input'), 'default');
  await type(q('username-input'), 'qr-employee');
  await type(q('password-input'), 'pw-123456');
  await type(q('image-captcha-input'), 'ab12');
}

beforeEach(() => {
  secureMem.clear();
  useAuthStore.getState().clear();
  useBSessionStore.getState().clear();
  navStub.navigate.mockClear();
  navStub.goBack.mockClear();
  cam.scanHandler = null;
  cam.permission = { granted: true };
  cam.permissionRequests = 0;
});

afterEach(() => {
  vi.unstubAllGlobals();
  if (root) {
    await_unmount();
  }
});

// ————— AC-7 员工账密登录（B 会话落盘 + 双会话隔离） —————

describe('REQ20261119040838 AC-7 APP 员工账密登录', () => {
  it('should_submitEmployeeLoginWithCaptchaFields_when_policyEnabled', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      const p = r.url.pathname;
      if (p === '/admin/v1/auth/captcha-policy') {
        return envelope({ captchaEnabled: true });
      }
      if (p === '/admin/v1/auth/captcha') {
        return envelope({ captchaId: 'captcha-9', imageBase64Data: 'data:image/png;base64,iVBORw0KGgo=' });
      }
      if (p === '/admin/v1/auth/tokens' && r.method === 'POST') {
        return envelope(issued());
      }
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await render(createElement(ThemeProvider, null, createElement(LoginScreen)));
    await openEmployeeTabAndFill();
    await click(q('employee-login'));

    // 提交体：租户 + 凭据 + 图形验证码两字段（策略开，AC-7）
    const posts = callsTo(fetchMock, '/admin/v1/auth/tokens', 'POST');
    expect(posts).toHaveLength(1);
    expect(parseBody(posts[0]?.body)).toEqual({
      tenantCode: 'default',
      username: 'qr-employee',
      password: 'pw-123456',
      captchaId: 'captcha-9',
      captchaCode: 'ab12',
    });
  });

  it('should_persistBSessionAndStayIsolatedFromCSession_when_employeeLoginSucceeds', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.url.pathname === '/admin/v1/auth/captcha-policy') return envelope({ captchaEnabled: false });
      if (r.url.pathname === '/admin/v1/auth/tokens' && r.method === 'POST') return envelope(issued());
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await render(createElement(ThemeProvider, null, createElement(LoginScreen)));
    await click(q('login-mode-employee'));
    await act(async () => { await flush(); });

    // 策略关：不渲染图形验证码输入框（契约确认记录②），提交体不含 captcha 字段
    expect(byLabel('图形验证码')).toBeNull();
    await type(q('tenant-input'), 'default');
    await type(q('username-input'), 'qr-employee');
    await type(q('password-input'), 'pw-123456');
    await click(q('employee-login'));

    const posts = callsTo(fetchMock, '/admin/v1/auth/tokens', 'POST');
    expect(parseBody(posts[0]?.body)).toEqual({
      tenantCode: 'default', username: 'qr-employee', password: 'pw-123456',
    });

    // B 会话：内存 store + 安全存储独立键 acme.auth.bsession.v1（userId wire string）
    const bSession = useBSessionStore.getState().session;
    expect(bSession).not.toBeNull();
    expect(typeof bSession!.userId).toBe('string');
    expect(bSession!.userId).toBe('2002');
    expect(bSession!.username).toBe('qr-employee');
    const persisted = JSON.parse(secureMem.get('acme.auth.bsession.v1')!) as { userId: string };
    expect(persisted.userId).toBe('2002');

    // 双会话隔离：C 端会话仍为空、C 键未写入；不发生主界面导航（B 会话不进主 Tabs）
    expect(useAuthStore.getState().session).toBeNull();
    expect(secureMem.get('acme.auth.session.v1')).toBeUndefined();
    expect(navStub.navigate).not.toHaveBeenCalled();

    // 有 B 会话 → 扫码确认入口出现并导航 QrConfirm
    await click(q('qr-confirm-entry'));
    expect(navStub.navigate).toHaveBeenCalledWith(QR_CONFIRM_ROUTE);
  });

  it('should_guideEmployeeLoginFirst_when_noBSession', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope({ captchaEnabled: false })));
    await render(createElement(ThemeProvider, null, createElement(LoginScreen)));
    await click(q('login-mode-employee'));

    // 无 B 会话：入口不渲染，仅展示先员工登录引导
    expect(qq('qr-confirm-entry')).toBeNull();
    expect(container.textContent).toContain('请先完成员工账密登录');
  });
});

// ————— B 会话校验（access-only） —————

describe('REQ20261119040838 B 会话存储', () => {
  it('should_acceptBSessionWithoutRefreshToken_when_hydratedFromSecureStore', async () => {
    // access-only 变体（无 refreshToken）也是合法 B 会话
    secureMem.set('acme.auth.bsession.v1', JSON.stringify({
      accessToken: 'b-access-1',
      accessExpiresAtMs: Date.now() + 30 * 60_000,
      userId: '2002',
      username: 'qr-employee',
      nickname: '扫码员工',
      tenantCode: 'default',
    }));
    await useBSessionStore.getState().hydrate();
    expect(useBSessionStore.getState().session?.accessToken).toBe('b-access-1');
    expect(useBSessionStore.getState().hydrated).toBe(true);
    // C 端会话不受影响（独立键独立存储）
    expect(useAuthStore.getState().session).toBeNull();
  });

  it('should_dropBSession_when_locallyExpiredOrCorrupted', async () => {
    // 本地已过期（无 refreshToken 不可续）→ 丢弃
    secureMem.set('acme.auth.bsession.v1', JSON.stringify({
      accessToken: 'b-access-1',
      accessExpiresAtMs: Date.now() - 1_000,
      userId: '2002',
      username: 'qr-employee',
      nickname: null,
      tenantCode: 'default',
    }));
    await useBSessionStore.getState().hydrate();
    expect(useBSessionStore.getState().session).toBeNull();

    // 字段缺失（损坏数据）→ 拒绝恢复
    secureMem.set('acme.auth.bsession.v1', JSON.stringify({ accessToken: 'x' }));
    await useBSessionStore.getState().hydrate();
    expect(useBSessionStore.getState().session).toBeNull();
    expect(useBSessionStore.getState().hydrated).toBe(true);
  });

  it('should_carryBearerTokenFromBSession_when_bAuthHeadersCalled', () => {
    expect(bAuthHeaders()).toEqual({});
    seedBSession();
    expect(bAuthHeaders()).toEqual({ Authorization: 'Bearer b-token-1' });
  });
});

// ————— AC-8 扫码确认（info/confirm/cancel 路径与 B 令牌头） —————

describe('REQ20261119040838 AC-8 QrConfirmScreen', () => {
  const setupQrApi = (infoView: unknown = { status: 'scanned', createdAt: '2026-11-19T04:00:00.000Z' }) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      const p = r.url.pathname;
      if (p.endsWith('/info') && r.method === 'GET') return envelope(infoView);
      if (p.endsWith('/confirm') && r.method === 'POST') return envelope(issued());
      if (p.endsWith('/cancel') && r.method === 'POST') return new Response(null, { status: 200 });
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);
    return { fetchMock };
  };

  const renderQrConfirm = async () => {
    await render(
      createElement(ThemeProvider, null, createElement(ToastProvider, null, createElement(QrConfirmScreen))),
    );
  };

  const scan = async (payload: string) => {
    await act(async () => {
      cam.scanHandler?.({ data: payload });
      await flush();
    });
    await act(async () => { await flush(); });
  };

  it('should_requestInfoWithBearerToken_when_cameraScansQrPayload', async () => {
    const { fetchMock } = setupQrApi();
    seedBSession();
    await renderQrConfirm();

    // 相机就绪（权限 granted）→ 相机预览挂载
    expect(q('camera-box')).toBeTruthy();

    // 相机回调：qrPayload=JSON{t}（契约⑤）→ qrInfo（B 令牌 Authorization 头）
    await scan(JSON.stringify({ t: 'tk-42' }));

    const infos = callsTo(fetchMock, '/admin/v1/auth/qr-tickets/tk-42/info', 'GET');
    expect(infos).toHaveLength(1);
    const headers = new Headers(infos[0]?.init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer b-token-1');

    // 票据信息展示（状态文案复用 web 扫码域键）+ 确认/取消按钮出现
    expect(q('qr-info')).toBeTruthy();
    expect(container.textContent).toContain('请在手机上点击确认');
  });

  it('should_rejectNonQrPayload_when_scanDataInvalid', async () => {
    const { fetchMock } = setupQrApi();
    seedBSession();
    await renderQrConfirm();

    // 非 JSON / 缺 t 字段：提示二维码无效且不发起请求
    await scan('not-a-payload');
    expect(q('qr-info-error')).toBeTruthy();
    expect(container.textContent).toContain('二维码无效');
    await scan(JSON.stringify({ x: 1 }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('should_confirmThenToastAndGoBack_when_confirmClicked', async () => {
    const { fetchMock } = setupQrApi();
    seedBSession();
    await renderQrConfirm();
    await scan(JSON.stringify({ t: 'tk-42' }));

    await click(q('qr-confirm'));

    const confirms = callsTo(fetchMock, '/admin/v1/auth/qr-tickets/tk-42/confirm', 'POST');
    expect(confirms).toHaveLength(1);
    const headers = new Headers(confirms[0]?.init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer b-token-1');
    expect(parseBody(confirms[0]?.body)).toEqual({});
    // 成功 toast + 返回上一屏
    expect(container.textContent).toContain('已确认登录');
    expect(navStub.goBack).toHaveBeenCalledTimes(1);
  });

  it('should_cancelTicketAndGoBack_when_cancelClicked', async () => {
    const { fetchMock } = setupQrApi();
    seedBSession();
    await renderQrConfirm();
    await scan(JSON.stringify({ t: 'tk-42' }));

    await click(q('qr-cancel'));

    // 取消：POST cancel（void 端点 2xx 空体=成功）→ toast + 返回
    const cancels = callsTo(fetchMock, '/admin/v1/auth/qr-tickets/tk-42/cancel', 'POST');
    expect(cancels).toHaveLength(1);
    const headers = new Headers(cancels[0]?.init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer b-token-1');
    expect(container.textContent).toContain('已取消登录');
    expect(navStub.goBack).toHaveBeenCalledTimes(1);
  });

  it('should_fallBackToManualTicketEntry_when_cameraPermissionDenied', async () => {
    const { fetchMock } = setupQrApi();
    cam.permission = { granted: false };
    seedBSession();
    await renderQrConfirm();

    // 权限被拒：相机预览不挂载，展示手动输入备选 + 权限申请入口
    expect(q('camera-denied')).toBeTruthy();
    expect(qq('camera-preview')).toBeNull();
    await click(q('camera-request'));
    expect(cam.permissionRequests).toBe(1);

    // 手动输入 ticketId → 获取票据信息（同样携带 B 令牌）
    await type(q('ticket-input'), 'tk-manual');
    await click(q('qr-load-info'));

    const infos = callsTo(fetchMock, '/admin/v1/auth/qr-tickets/tk-manual/info', 'GET');
    expect(infos).toHaveLength(1);
    expect(new Headers(infos[0]?.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    expect(q('qr-info')).toBeTruthy();
  });

  it('should_showGuidanceWithoutRequest_when_noBSession', async () => {
    const { fetchMock } = setupQrApi();
    await renderQrConfirm();

    // 无 B 会话：仅引导文案，不挂相机、不发请求
    expect(qq('camera-box')).toBeNull();
    expect(container.textContent).toContain('请先完成员工账密登录');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// ————— AC-9 api 层路径与载荷（B 令牌显式头） —————

describe('REQ20261119040838 mobile qr api 层', () => {
  it('should_adminLoginAndUnwrapAdminTokenIssued_when_credentialsPosted', async () => {
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope(issued());
    }));

    const view = await authApi.adminLogin({
      tenantCode: 'default', username: 'qr-employee', password: 'pw', captchaId: 'c1', captchaCode: 'ab12',
    });

    expect(view.userId).toBe('2002');
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/tokens');
    expect(captured.current?.init?.method).toBe('POST');
    expect(parseBody(captured.current?.init?.body)).toEqual({
      tenantCode: 'default', username: 'qr-employee', password: 'pw', captchaId: 'c1', captchaCode: 'ab12',
    });
    // 公开端点：不带 Authorization
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBeNull();
  });

  it('should_requestInfoConfirmCancelByPath_when_qrApiCalled', async () => {
    seedBSession();
    const calls: Array<{ url: URL; init?: RequestInit }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: new URL(String(input), 'http://localhost'), init });
      const path = calls[calls.length - 1]!.url.pathname;
      if (path.endsWith('/cancel')) return new Response(null, { status: 200 });
      if (path.endsWith('/confirm')) return envelope(issued());
      return envelope({ status: 'scanned', createdAt: '2026-11-19T04:00:00.000Z' });
    }));

    const info = await authApi.qrInfo('tk-7');
    const confirmed = await authApi.qrConfirm('tk-7');
    const cancelled = await authApi.qrCancel('tk-7');

    expect(info).toEqual({ status: 'scanned', createdAt: '2026-11-19T04:00:00.000Z' });
    expect(confirmed.userId).toBe('2002');
    expect(cancelled).toBeUndefined();
    expect(calls.map((c) => `${c.init?.method ?? 'GET'} ${c.url.pathname}`)).toEqual([
      'GET /admin/v1/auth/qr-tickets/tk-7/info',
      'POST /admin/v1/auth/qr-tickets/tk-7/confirm',
      'POST /admin/v1/auth/qr-tickets/tk-7/cancel',
    ]);
    // B 端认证端点：三个请求都显式携带 B 会话令牌
    for (const c of calls) {
      expect(new Headers(c.init?.headers).get('Authorization')).toBe('Bearer b-token-1');
    }
  });
});

// ————— AC-9 i18n 资源成对 —————

function leafPaths(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') {
    return [prefix];
  }
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    leafPaths(v, prefix ? `${prefix}.${k}` : k),
  );
}

describe('REQ20261119040838 i18n 资源（AC-9）', () => {
  it('should_pair_zh_and_en_resource_shapes_when_qrLoginKeysAdded', () => {
    const data = (i18n as unknown as { store: { data: Record<string, { translation: unknown }> } }).store.data;
    const zh = leafPaths(data['zh-CN']!.translation).sort();
    const en = leafPaths(data['en-US']!.translation).sort();
    expect(zh.length).toBeGreaterThan(0);
    expect(en).toEqual(zh);
  });

  it('should_resolve_new_qrLogin_keys_when_locale_zh', () => {
    expect(i18n.t('login.qr.tab')).toBe('扫码登录');
    expect(i18n.t('login.qr.statusCreated')).toBe('待扫描');
    expect(i18n.t('login.qr.refresh')).toBe('刷新二维码');
    expect(i18n.t('login.mobile.modeEmployee')).toBe('员工账密');
    expect(i18n.t('login.mobile.qrConfirmEntry')).toBe('扫码确认登录');
    expect(i18n.t('login.mobile.qrConfirmButton')).toBe('确认登录');
  });
});
